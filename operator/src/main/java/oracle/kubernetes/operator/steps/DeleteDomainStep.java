// Copyright (c) 2017, 2020, Oracle Corporation and/or its affiliates.  All rights reserved.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.steps;

import java.util.stream.Collectors;

import io.kubernetes.client.models.V1PersistentVolumeClaimList;
import io.kubernetes.client.models.V1PersistentVolumeList;
import io.kubernetes.client.models.V1ServiceList;
import oracle.kubernetes.operator.Main;
import oracle.kubernetes.operator.calls.CallResponse;
import oracle.kubernetes.operator.helpers.AuthorizationProxy;
import oracle.kubernetes.operator.helpers.CallBuilder;
import oracle.kubernetes.operator.helpers.ConfigMapHelper;
import oracle.kubernetes.operator.helpers.DomainPresenceInfo;
import oracle.kubernetes.operator.helpers.PodHelper;
import oracle.kubernetes.operator.work.NextAction;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;

import static oracle.kubernetes.operator.LabelConstants.forDomainUidSelector;
import static oracle.kubernetes.operator.LabelConstants.getCreatedbyOperatorSelector;

public class DeleteDomainStep extends Step {
  private final DomainPresenceInfo info;
  private final String namespace;
  private final String domainUid;

  public DeleteDomainStep(DomainPresenceInfo info, String namespace, String domainUid) {
    super(null);
    this.info = info;
    this.namespace = namespace;
    this.domainUid = domainUid;
  }

  @Override
  public NextAction apply(Packet packet) {
    Step serverDownStep = null;
    // we don't delete PV unless we have the permission
    if (!Main.isAccessAllowed(AuthorizationProxy.Resource.PERSISTENTVOLUMES, AuthorizationProxy.Operation.delete)) {
      serverDownStep = Step.chain(
          deletePods(),
          deleteServices(),
          deletePersistentVolumeClaims(),
          ConfigMapHelper.deleteDomainIntrospectorConfigMapStep(domainUid, namespace, getNext()));
    } else {
      serverDownStep = Step.chain(
          deletePods(),
          deleteServices(),
          deletePersistentVolumes(),
          deletePersistentVolumeClaims(),
          ConfigMapHelper.deleteDomainIntrospectorConfigMapStep(domainUid, namespace, getNext()));
    }

    if (info != null) {
      serverDownStep =
          new ServerDownIteratorStep(
              info.getServerPods().map(PodHelper::getPodServerName).collect(Collectors.toList()),
              serverDownStep);
    }

    return doNext(serverDownStep, packet);
  }

  private Step deleteServices() {
    return new CallBuilder()
        .withLabelSelectors(forDomainUidSelector(domainUid), getCreatedbyOperatorSelector())
        .listServiceAsync(
            namespace,
            new ActionResponseStep<V1ServiceList>() {
              Step createSuccessStep(V1ServiceList result, Step next) {
                return new DeleteServiceListStep(result.getItems(), next);
              }
            });
  }

  private Step deletePods() {
    return new CallBuilder()
        .withLabelSelectors(forDomainUidSelector(domainUid), getCreatedbyOperatorSelector())
        .deleteCollectionPodAsync(namespace, new DefaultResponseStep<>(null));
  }

  private Step deletePersistentVolumes() {
    return new CallBuilder()
        .withLabelSelectors(forDomainUidSelector(domainUid), getCreatedbyOperatorSelector())
        .listPersistentVolumeAsync(
            new ActionResponseStep<V1PersistentVolumeList>() {
              @Override
              Step createSuccessStep(V1PersistentVolumeList result, Step next) {
                return new DeletePersistentVolumeListStep(result.getItems(), next);
              }
            });
  }

  private Step deletePersistentVolumeClaims() {
    return new CallBuilder()
        .withLabelSelectors(forDomainUidSelector(domainUid), getCreatedbyOperatorSelector())
        .listPersistentVolumeClaimAsync(
            namespace,
            new ActionResponseStep<V1PersistentVolumeClaimList>() {
              @Override
              Step createSuccessStep(V1PersistentVolumeClaimList result, Step next) {
                return new DeletePersistentVolumeClaimListStep(result.getItems(), next);
              }
            });
  }

  /**
   * A response step which treats a NOT_FOUND status as success with a null result. On success with
   * a non-null response, runs a specified new step before continuing the step chain.
   */
  abstract static class ActionResponseStep<T> extends DefaultResponseStep<T> {
    ActionResponseStep() {
    }

    abstract Step createSuccessStep(T result, Step next);

    @Override
    public NextAction onSuccess(Packet packet, CallResponse<T> callResponse) {
      return callResponse.getResult() == null
          ? doNext(packet)
          : doNext(createSuccessStep(callResponse.getResult(), getNext()), packet);
    }
  }
}
