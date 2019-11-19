// Copyright (c) 2017, 2019, Oracle Corporation and/or its affiliates.  All rights reserved.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import javax.annotation.Nonnull;

import io.kubernetes.client.models.V1EventList;
import io.kubernetes.client.models.V1Namespace;
import io.kubernetes.client.models.V1Pod;
import io.kubernetes.client.models.V1PodList;
import io.kubernetes.client.models.V1Service;
import io.kubernetes.client.models.V1ServiceList;
import io.kubernetes.client.util.Watch;
import oracle.kubernetes.operator.calls.CallResponse;
import oracle.kubernetes.operator.helpers.CallBuilder;
import oracle.kubernetes.operator.helpers.CallBuilderFactory;
import oracle.kubernetes.operator.helpers.ClientPool;
import oracle.kubernetes.operator.helpers.ConfigMapHelper;
import oracle.kubernetes.operator.helpers.CrdHelper;
import oracle.kubernetes.operator.helpers.DomainPresenceInfo;
import oracle.kubernetes.operator.helpers.HealthCheckHelper;
import oracle.kubernetes.operator.helpers.KubernetesVersion;
import oracle.kubernetes.operator.helpers.PodHelper;
import oracle.kubernetes.operator.helpers.ResponseStep;
import oracle.kubernetes.operator.helpers.ServiceHelper;
import oracle.kubernetes.operator.logging.LoggingFacade;
import oracle.kubernetes.operator.logging.LoggingFactory;
import oracle.kubernetes.operator.logging.MessageKeys;
import oracle.kubernetes.operator.rest.RestConfigImpl;
import oracle.kubernetes.operator.rest.RestServer;
import oracle.kubernetes.operator.steps.ConfigMapAfterStep;
import oracle.kubernetes.operator.work.Component;
import oracle.kubernetes.operator.work.Container;
import oracle.kubernetes.operator.work.ContainerResolver;
import oracle.kubernetes.operator.work.Engine;
import oracle.kubernetes.operator.work.Fiber;
import oracle.kubernetes.operator.work.Fiber.CompletionCallback;
import oracle.kubernetes.operator.work.FiberGate;
import oracle.kubernetes.operator.work.NextAction;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;
import oracle.kubernetes.operator.work.ThreadFactorySingleton;
import oracle.kubernetes.weblogic.domain.model.Domain;
import oracle.kubernetes.weblogic.domain.model.DomainList;
import org.apache.commons.lang.StringUtils;
import org.joda.time.DateTime;

/** A Kubernetes Operator for WebLogic. */
public class Main {
  private static final LoggingFacade LOGGER = LoggingFactory.getLogger("Operator", "Operator");

  private static final String DPI_MAP = "DPI_MAP";

  private static final Container container = new Container();
  private static final ThreadFactory threadFactory = new WrappedThreadFactory();
  private static final ScheduledExecutorService wrappedExecutorService =
      Engine.wrappedExecutorService("operator", container);
  private static final TuningParameters tuningAndConfig;
  private static final CallBuilderFactory callBuilderFactory = new CallBuilderFactory();
  private static final Map<String, AtomicBoolean> isNamespaceStarted = new ConcurrentHashMap<>();
  private static final Map<String, AtomicBoolean> isNamespaceStopping = new ConcurrentHashMap<>();
  private static final Map<String, ConfigMapWatcher> configMapWatchers = new ConcurrentHashMap<>();
  private static final Map<String, DomainWatcher> domainWatchers = new ConcurrentHashMap<>();
  private static final Map<String, EventWatcher> eventWatchers = new ConcurrentHashMap<>();
  private static final Map<String, ServiceWatcher> serviceWatchers = new ConcurrentHashMap<>();
  private static final Map<String, PodWatcher> podWatchers = new ConcurrentHashMap<>();
  private static Function<String,String> getHelmVariable = System::getenv;
  private static final String operatorNamespace = computeOperatorNamespace();
  private static final AtomicReference<DateTime> lastFullRecheck =
      new AtomicReference<>(DateTime.now());
  private static final DomainProcessorDelegateImpl delegate = new DomainProcessorDelegateImpl();
  private static final DomainProcessor processor = new DomainProcessorImpl(delegate);
  private static final String READINESS_PROBE_FAILURE_EVENT_FILTER =
      "reason=Unhealthy,type=Warning,involvedObject.fieldPath=spec.containers{weblogic-server}";
  private static final Semaphore shutdownSignal = new Semaphore(0);
  private static Engine engine = new Engine(wrappedExecutorService);
  private static String principal;
  private static KubernetesVersion version = null;

  static {
    try {
      // suppress System.err since we catch all necessary output with Logger
      OutputStream output = new FileOutputStream("/dev/null");
      PrintStream nullOut = new PrintStream(output);
      System.setErr(nullOut);

      ClientPool.initialize(threadFactory);

      TuningParameters.initializeInstance(wrappedExecutorService, "/operator/config");
      tuningAndConfig = TuningParameters.getInstance();
    } catch (IOException e) {
      LOGGER.warning(MessageKeys.EXCEPTION, e);
      throw new RuntimeException(e);
    }
  }

  static {
    container
        .getComponents()
        .put(
            ProcessingConstants.MAIN_COMPONENT_NAME,
            Component.createFor(
                ScheduledExecutorService.class,
                wrappedExecutorService,
                TuningParameters.class,
                tuningAndConfig,
                ThreadFactory.class,
                threadFactory,
                callBuilderFactory));
  }

  /**
   * Entry point.
   *
   * @param args none, ignored
   */
  public static void main(String[] args) {
    try (final InputStream stream = Main.class.getResourceAsStream("/version.properties")) {
      Properties buildProps = new Properties();
      buildProps.load(stream);

      String operatorVersion = buildProps.getProperty("git.build.version");
      String operatorImpl =
          buildProps.getProperty("git.branch")
              + "."
              + buildProps.getProperty("git.commit.id.abbrev");
      String operatorBuildTime = buildProps.getProperty("git.build.time");

      // print startup log message
      LOGGER.info(MessageKeys.OPERATOR_STARTED, operatorVersion, operatorImpl, operatorBuildTime);
    } catch (IOException e) {
      LOGGER.warning(MessageKeys.EXCEPTION, e);
    }

    try {
      engine.getExecutor().execute(Main::begin);

      // now we just wait until the pod is terminated
      waitForDeath();

      // stop the REST server
      stopRestServer();
    } finally {
      LOGGER.info(MessageKeys.OPERATOR_SHUTTING_DOWN);
    }
  }

  private static void begin() {
    String serviceAccountName =
        Optional.ofNullable(tuningAndConfig.get("serviceaccount")).orElse("default");
    principal = "system:serviceaccount:" + operatorNamespace + ":" + serviceAccountName;

    LOGGER.info(MessageKeys.OP_CONFIG_NAMESPACE, operatorNamespace);
    JobWatcher.defineFactory(
        threadFactory, tuningAndConfig.getWatchTuning(), Main::isNamespaceStopping);

    Collection<String> targetNamespaces = getTargetNamespaces();
    LOGGER.info(MessageKeys.OP_CONFIG_TARGET_NAMESPACES, StringUtils.join(targetNamespaces, ", "));
    LOGGER.info(MessageKeys.OP_CONFIG_SERVICE_ACCOUNT, serviceAccountName);

    try {
      version = HealthCheckHelper.performK8sVersionCheck();

      runSteps(
          new StartNamespaceWatcherStep(CrdHelper.createDomainCrdStep(version, 
              new StartNamespacesStep(targetNamespaces))), 
          Main::completeBegin);
    } catch (Throwable e) {
      LOGGER.warning(MessageKeys.EXCEPTION, e);
    }
  }

  private static void completeBegin() {
    try {
      // start the REST server
      startRestServer(principal, isNamespaceStopping.keySet());

      // start periodic retry and recheck
      int recheckInterval = tuningAndConfig.getMainTuning().targetNamespaceRecheckIntervalSeconds;
      engine
          .getExecutor()
          .scheduleWithFixedDelay(
              recheckDomains(), recheckInterval, recheckInterval, TimeUnit.SECONDS);

      // Wait until all other initialization is done before marking ready and
      // starting liveness thread

      // mark ready and start liveness thread
      markReadyAndStartLivenessThread();

    } catch (Throwable e) {
      LOGGER.warning(MessageKeys.EXCEPTION, e);
    }
  }

  private static void stopNamespace(String ns) {
    processor.stopNamespace(ns);
    AtomicBoolean stopping = isNamespaceStopping.remove(ns);
    if (stopping != null) {
      stopping.set(true);
    }
    isNamespaceStarted.remove(ns);
    domainWatchers.remove(ns);
    eventWatchers.remove(ns);
    podWatchers.remove(ns);
    serviceWatchers.remove(ns);
    JobWatcher.removeNamespace(ns);
  }

  private static void stopNamespaces(Collection<String> namespacesToStop) {
    for (String ns : namespacesToStop) {
      stopNamespace(ns);
    }
  }

  private static AtomicBoolean isNamespaceStopping(String ns) {
    return isNamespaceStopping.computeIfAbsent(ns, (key) -> new AtomicBoolean(false));
  }

  private static void runSteps(Step firstStep) {
    runSteps(firstStep, null);
  }

  private static void runSteps(Step firstStep, Runnable completionAction) {
    Fiber f = engine.createFiber();
    f.start(firstStep, new Packet(), andThenDo(completionAction));
  }

  private static NullCompletionCallback andThenDo(Runnable completionAction) {
    return new NullCompletionCallback(completionAction);
  }

  static Runnable recheckDomains() {
    return () -> {
      Collection<String> targetNamespaces = getTargetNamespaces();

      // Check for removed namespaces
      Set<String> namespacesToStop = new TreeSet<>(isNamespaceStopping.keySet());
      namespacesToStop.removeAll(targetNamespaces);
      stopNamespaces(namespacesToStop);

      Collection<String> namespacesToStart = targetNamespaces;
      int recheckInterval = tuningAndConfig.getMainTuning().domainPresenceRecheckIntervalSeconds;
      DateTime now = DateTime.now();
      if (lastFullRecheck.get().plusSeconds(recheckInterval).isBefore(now)) {
        lastFullRecheck.set(now);
      } else {
        namespacesToStart = new TreeSet<>(targetNamespaces);
        namespacesToStart.removeAll(isNamespaceStarted.keySet());
      }

      if (!namespacesToStart.isEmpty()) {
        runSteps(new StartNamespacesStep(namespacesToStart));
      }
    };
  }

  static Step readExistingResources(String operatorNamespace, String ns) {
    return Step.chain(
        new ReadExistingResourcesBeforeStep(),
        ConfigMapHelper.createScriptConfigMapStep(operatorNamespace, ns),
        createConfigMapStep(ns),
        readExistingPods(ns),
        readExistingEvents(ns),
        readExistingServices(ns),
        readExistingDomains(ns));
  }

  private static Step readExistingDomains(String ns) {
    LOGGER.fine(MessageKeys.LISTING_DOMAINS);
    return callBuilderFactory.create().listDomainAsync(ns, new DomainListStep(ns));
  }

  private static Step readExistingServices(String ns) {
    return new CallBuilder()
        .withLabelSelectors(LabelConstants.DOMAINUID_LABEL, LabelConstants.CREATEDBYOPERATOR_LABEL)
        .listServiceAsync(ns, new ServiceListStep(ns));
  }

  private static Step readExistingEvents(String ns) {
    return new CallBuilder()
        .withFieldSelector(Main.READINESS_PROBE_FAILURE_EVENT_FILTER)
        .listEventAsync(ns, new EventListStep(ns));
  }

  private static Step readExistingPods(String ns) {
    return new CallBuilder()
        .withLabelSelectors(LabelConstants.DOMAINUID_LABEL, LabelConstants.CREATEDBYOPERATOR_LABEL)
        .listPodAsync(ns, new PodListStep(ns));
  }

  private static ConfigMapAfterStep createConfigMapStep(String ns) {
    return new ConfigMapAfterStep(
        ns,
        configMapWatchers,
        tuningAndConfig.getWatchTuning(),
        isNamespaceStopping(ns),
        processor::dispatchConfigMapWatch);
  }

  /**
   * Obtain the list of target namespaces.
   *
   * @return the collection of target namespace names
   */
  @SuppressWarnings("SameParameterValue")
  private static Collection<String> getTargetNamespaces(String tnValue, String namespace) {
    Collection<String> targetNamespaces = new ArrayList<>();

    if (tnValue != null) {
      StringTokenizer st = new StringTokenizer(tnValue, ",");
      while (st.hasMoreTokens()) {
        targetNamespaces.add(st.nextToken().trim());
      }
    }

    // If no namespaces were found, default to the namespace of the operator
    if (targetNamespaces.isEmpty()) {
      targetNamespaces.add(namespace);
    }

    return targetNamespaces;
  }

  private static Collection<String> getTargetNamespaces() {
    return getTargetNamespaces(
        Optional.ofNullable(getHelmVariable.apply("OPERATOR_TARGET_NAMESPACES"))
            .orElse(tuningAndConfig.get("targetNamespaces")),
        operatorNamespace);
  }

  private static void startRestServer(String principal, Collection<String> targetNamespaces)
      throws Exception {
    RestServer.create(new RestConfigImpl(principal, targetNamespaces));
    RestServer.getInstance().start(container);
  }

  // -----------------------------------------------------------------------------
  //
  // Below this point are methods that are called primarily from watch handlers,
  // after watch events are received.
  //
  // -----------------------------------------------------------------------------

  private static void stopRestServer() {
    RestServer.getInstance().stop();
    RestServer.destroy();
  }

  private static void markReadyAndStartLivenessThread() {
    try {
      OperatorReady.create();

      LOGGER.info(MessageKeys.STARTING_LIVENESS_THREAD);
      // every five seconds we need to update the last modified time on the liveness file
      wrappedExecutorService.scheduleWithFixedDelay(new OperatorLiveness(), 5, 5, TimeUnit.SECONDS);
    } catch (IOException io) {
      LOGGER.severe(MessageKeys.EXCEPTION, io);
    }
  }

  private static void waitForDeath() {
    Runtime.getRuntime().addShutdownHook(new Thread(shutdownSignal::release));

    try {
      shutdownSignal.acquire();
    } catch (InterruptedException ignore) {
      Thread.currentThread().interrupt();
    }

    isNamespaceStopping.forEach((key, value) -> value.set(true));
  }

  private static EventWatcher createEventWatcher(String ns, String initialResourceVersion) {
    return EventWatcher.create(
        threadFactory,
        ns,
        READINESS_PROBE_FAILURE_EVENT_FILTER,
        initialResourceVersion,
        tuningAndConfig.getWatchTuning(),
        processor::dispatchEventWatch,
        isNamespaceStopping(ns));
  }

  private static PodWatcher createPodWatcher(String ns, String initialResourceVersion) {
    return PodWatcher.create(
        threadFactory,
        ns,
        initialResourceVersion,
        tuningAndConfig.getWatchTuning(),
        processor::dispatchPodWatch,
        isNamespaceStopping(ns));
  }

  private static ServiceWatcher createServiceWatcher(String ns, String initialResourceVersion) {
    return ServiceWatcher.create(
        threadFactory,
        ns,
        initialResourceVersion,
        tuningAndConfig.getWatchTuning(),
        processor::dispatchServiceWatch,
        isNamespaceStopping(ns));
  }

  private static DomainWatcher createDomainWatcher(String ns, String initialResourceVersion) {
    return DomainWatcher.create(
        threadFactory,
        ns,
        initialResourceVersion,
        tuningAndConfig.getWatchTuning(),
        processor::dispatchDomainWatch,
        isNamespaceStopping(ns));
  }

  private static NamespaceWatcher createNamespaceWatcher(String initialResourceVersion) {
    return NamespaceWatcher.create(
        threadFactory,
        initialResourceVersion,
        tuningAndConfig.getWatchTuning(),
        Main::dispatchNamespaceWatch,
        new AtomicBoolean(false));
  }

  private static String computeOperatorNamespace() {
    return Optional.ofNullable(getHelmVariable.apply("OPERATOR_NAMESPACE")).orElse("default");
  }

  private static void dispatchNamespaceWatch(Watch.Response<V1Namespace> item) {
    Collection<String> targetNamespaces = getTargetNamespaces();
    V1Namespace c = item.object;
    LOGGER.info(MessageKeys.ENTER_METHOD, "dispatchNamespaceWatch", " type = " 
        + item.type + " targetNamespaces = " + targetNamespaces + " c=" + c);
    if (c != null) {
      String ns = c.getMetadata().getName();
      switch (item.type) {
        case "ADDED":
          LOGGER.info(MessageKeys.ENTER_METHOD, "Adding namespace " + ns
              + " isrunning = " + delegate.isNamespaceRunning(ns));
          // We only create the config map when a namespace is added
          // the rest of the operations to standup domains in a new namespace
          // will continue to be handled in recheckDomain method, which periodcally
          // checks for new domain resources in the target name spaces.
          if (targetNamespaces.contains(ns) && ! delegate.isNamespaceRunning(ns)) {
            runSteps(Step.chain(
                ConfigMapHelper.createScriptConfigMapStep(operatorNamespace, ns),
                createConfigMapStep(ns)));
          }
          // if (targetNamespaces.contains(ns)) {
            // LOGGER.info(MessageKeys.ENTER_METHOD, "Before creating config map for namespace " 
            //  + ns);
            // Collection<String> nsToStart = new ArrayList<>();
            // nsToStart.add(ns);
            // runSteps(new StartNamespacesStep(nsToStart));
            // LOGGER.info(MessageKeys.ENTER_METHOD, "After creating config map for namespace " 
            //     + ns);
          // }
          break;

        case "DELETED":
          // mark the namespace is stopping and it will be stopped the next time 
          // when recheckDomains is triggered
          LOGGER.info(MessageKeys.ENTER_METHOD, "Deleting namespace " + ns);
          if (targetNamespaces.contains(ns) && delegate.isNamespaceRunning(ns)) {
            isNamespaceStopping.put(ns, new AtomicBoolean(true));
          }
     
          // if (targetNamespaces.contains(ns)) {
          //   stopNamespace(ns);
            // if (targetNamespaces.contains(ns) && delegate.isNamespaceRunning(ns)) {
            // isNamespaceStopping.put(ns, new AtomicBoolean(true));
            // LOGGER.info(MessageKeys.ENTER_METHOD, "Deleted namespace " + ns);
          // }
          break;

        case "MODIFIED":
        case "ERROR":
        default:
      }
    }
  }

  private static class WrappedThreadFactory implements ThreadFactory {
    private final ThreadFactory delegate = ThreadFactorySingleton.getInstance();

    @Override
    public Thread newThread(@Nonnull Runnable r) {
      return delegate.newThread(
          () -> {
            ContainerResolver.getDefault().enterContainer(container);
            r.run();
          });
    }
  }

  private static class StartNamespaceWatcherStep extends Step {

    StartNamespaceWatcherStep(Step next) {
      super(next); 
    }

    @Override
    public NextAction apply(Packet packet) {
      createNamespaceWatcher("1");
      return doNext(packet);
    }
  }

  private static class StartNamespacesStep extends Step {
    private final Collection<String> targetNamespaces;

    StartNamespacesStep(Collection<String> targetNamespaces) {
      this.targetNamespaces = targetNamespaces;
    }

    @Override
    protected String getDetail() {
      return String.join(",", targetNamespaces);
    }

    @Override
    public NextAction apply(Packet packet) {
      // check for any existing resources and add the watches on them
      // this would happen when the Domain was running BEFORE the Operator starts up
      Collection<StepAndPacket> startDetails = new ArrayList<>();
      for (String ns : targetNamespaces) {
        startDetails.add(
            new StepAndPacket(
                Step.chain(
                    new StartNamespaceBeforeStep(ns), readExistingResources(operatorNamespace, ns)),
                packet.clone()));
      }
      return doForkJoin(getNext(), packet, startDetails);
    }
  }

  private static class StartNamespaceBeforeStep extends Step {
    private final String ns;

    StartNamespaceBeforeStep(String ns) {
      this.ns = ns;
    }

    @Override
    public NextAction apply(Packet packet) {
      AtomicBoolean a = isNamespaceStarted.computeIfAbsent(ns, (key) -> new AtomicBoolean(false));
      if (!a.getAndSet(true)) {
        try {
          HealthCheckHelper.performSecurityChecks(version, operatorNamespace, ns);
        } catch (Throwable e) {
          LOGGER.warning(MessageKeys.EXCEPTION, e);
        }

        return doNext(packet);
      }
      return doEnd(packet);
    }
  }

  private static class ReadExistingResourcesBeforeStep extends Step {
    @SuppressWarnings("rawtypes")
    @Override
    public NextAction apply(Packet packet) {
      packet.put(DPI_MAP, new ConcurrentHashMap());
      return doNext(packet);
    }
  }

  private static class DomainListStep extends ResponseStep<DomainList> {
    private final String ns;

    DomainListStep(String ns) {
      this.ns = ns;
    }

    @Override
    public NextAction onFailure(Packet packet, CallResponse<DomainList> callResponse) {
      return callResponse.getStatusCode() == CallBuilder.NOT_FOUND
          ? onSuccess(packet, callResponse)
          : super.onFailure(packet, callResponse);
    }

    @Override
    public NextAction onSuccess(Packet packet, CallResponse<DomainList> callResponse) {
      @SuppressWarnings("unchecked")
      Map<String, DomainPresenceInfo> dpis = (Map<String, DomainPresenceInfo>) packet.get(DPI_MAP);

      DomainProcessor x = packet.getSpi(DomainProcessor.class);
      DomainProcessor dp = x != null ? x : processor;

      Set<String> domainUids = new HashSet<>();
      if (callResponse.getResult() != null) {
        for (Domain dom : callResponse.getResult().getItems()) {
          String domainUid = dom.getDomainUid();
          domainUids.add(domainUid);
          DomainPresenceInfo info =
              dpis.compute(
                  domainUid,
                  (k, v) -> {
                    if (v == null) {
                      return new DomainPresenceInfo(dom);
                    }
                    v.setDomain(dom);
                    return v;
                  });
          info.setPopulated(true);
          dp.makeRightDomainPresence(info, true, false, false);
        }
      }

      dpis.forEach(
          (key, value) -> {
            if (!domainUids.contains(key)) {
              // This is a stranded DomainPresenceInfo.
              value.setDeleting(true);
              value.setPopulated(true);
              dp.makeRightDomainPresence(value, true, true, false);
            }
          });

      if (!domainWatchers.containsKey(ns)) {
        domainWatchers.put(
            ns, createDomainWatcher(ns, getResourceVersion(callResponse.getResult())));
      }
      return doNext(packet);
    }

    String getResourceVersion(DomainList result) {
      return result != null ? result.getMetadata().getResourceVersion() : "";
    }
  }

  private static class ServiceListStep extends ResponseStep<V1ServiceList> {
    private final String ns;

    ServiceListStep(String ns) {
      this.ns = ns;
    }

    @Override
    public NextAction onFailure(Packet packet, CallResponse<V1ServiceList> callResponse) {
      return callResponse.getStatusCode() == CallBuilder.NOT_FOUND
          ? onSuccess(packet, callResponse)
          : super.onFailure(packet, callResponse);
    }

    @Override
    public NextAction onSuccess(Packet packet, CallResponse<V1ServiceList> callResponse) {
      V1ServiceList result = callResponse.getResult();

      @SuppressWarnings("unchecked")
      Map<String, DomainPresenceInfo> dpis = (Map<String, DomainPresenceInfo>) packet.get(DPI_MAP);

      if (result != null) {
        for (V1Service service : result.getItems()) {
          String domainUid = ServiceHelper.getServiceDomainUid(service);
          if (domainUid != null) {
            DomainPresenceInfo info =
                dpis.computeIfAbsent(domainUid, k -> new DomainPresenceInfo(ns, domainUid));
            ServiceHelper.addToPresence(info, service);
          }
        }
      }

      if (!serviceWatchers.containsKey(ns)) {
        serviceWatchers.put(ns, createServiceWatcher(ns, getInitialResourceVersion(result)));
      }
      return doNext(packet);
    }

    private String getInitialResourceVersion(V1ServiceList result) {
      return result != null ? result.getMetadata().getResourceVersion() : "";
    }
  }

  private static class EventListStep extends ResponseStep<V1EventList> {
    private final String ns;

    EventListStep(String ns) {
      this.ns = ns;
    }

    @Override
    public NextAction onFailure(Packet packet, CallResponse<V1EventList> callResponse) {
      return callResponse.getStatusCode() == CallBuilder.NOT_FOUND
          ? onSuccess(packet, callResponse)
          : super.onFailure(packet, callResponse);
    }

    @Override
    public NextAction onSuccess(Packet packet, CallResponse<V1EventList> callResponse) {
      V1EventList result = callResponse.getResult();
      // don't bother processing pre-existing events

      if (!eventWatchers.containsKey(ns)) {
        eventWatchers.put(ns, createEventWatcher(ns, getInitialResourceVersion(result)));
      }
      return doNext(packet);
    }

    private String getInitialResourceVersion(V1EventList result) {
      return result != null ? result.getMetadata().getResourceVersion() : "";
    }
  }

  private static class PodListStep extends ResponseStep<V1PodList> {
    private final String ns;

    PodListStep(String ns) {
      this.ns = ns;
    }

    @Override
    public NextAction onFailure(Packet packet, CallResponse<V1PodList> callResponse) {
      return callResponse.getStatusCode() == CallBuilder.NOT_FOUND
          ? onSuccess(packet, callResponse)
          : super.onFailure(packet, callResponse);
    }

    @Override
    public NextAction onSuccess(Packet packet, CallResponse<V1PodList> callResponse) {
      V1PodList result = callResponse.getResult();

      @SuppressWarnings("unchecked")
      Map<String, DomainPresenceInfo> dpis = (Map<String, DomainPresenceInfo>) packet.get(DPI_MAP);

      if (result != null) {
        for (V1Pod pod : result.getItems()) {
          String domainUid = PodHelper.getPodDomainUid(pod);
          String serverName = PodHelper.getPodServerName(pod);
          if (domainUid != null && serverName != null) {
            DomainPresenceInfo info =
                dpis.computeIfAbsent(domainUid, k -> new DomainPresenceInfo(ns, domainUid));
            info.setServerPod(serverName, pod);
          }
        }
      }

      if (!podWatchers.containsKey(ns)) {
        podWatchers.put(ns, createPodWatcher(ns, getInitialResourceVersion(result)));
      }
      return doNext(packet);
    }

    private String getInitialResourceVersion(V1PodList result) {
      return result != null ? result.getMetadata().getResourceVersion() : "";
    }
  }

  private static class NullCompletionCallback implements CompletionCallback {
    private Runnable completionAction;

    NullCompletionCallback(Runnable completionAction) {
      this.completionAction = completionAction;
    }

    @Override
    public void onCompletion(Packet packet) {
      if (completionAction != null) {
        completionAction.run();
      }
    }

    @Override
    public void onThrowable(Packet packet, Throwable throwable) {
      LOGGER.severe(MessageKeys.EXCEPTION, throwable);
    }
  }

  private static class DomainProcessorDelegateImpl implements DomainProcessorDelegate {

    @Override
    public String getOperatorNamespace() {
      return operatorNamespace;
    }

    @Override
    public PodAwaiterStepFactory getPodAwaiterStepFactory(String namespace) {
      return podWatchers.get(namespace);
    }

    @Override
    public boolean isNamespaceRunning(String namespace) {
      AtomicBoolean isStopping = isNamespaceStopping.get(namespace);
      return (isStopping == null ? true : !isStopping.get());
    }

    @Override
    public KubernetesVersion getVersion() {
      return version;
    }

    @Override
    public FiberGate createFiberGate() {
      return new FiberGate(Main.engine);
    }

    @Override
    public void runSteps(Step firstStep) {
      Main.runSteps(firstStep);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long initialDelay, long delay, TimeUnit unit) {
      return Main.engine.getExecutor().scheduleWithFixedDelay(command, initialDelay, delay, unit);
    }
  }
}
