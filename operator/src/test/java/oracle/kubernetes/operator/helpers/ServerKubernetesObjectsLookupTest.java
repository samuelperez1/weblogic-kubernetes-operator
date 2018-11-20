// Copyright 2018, Oracle Corporation and/or its affiliates.  All rights reserved.
// Licensed under the Universal Permissive License v 1.0 as shown at
// http://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.helpers;

import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.junit.MatcherAssert.assertThat;

import com.google.code.tempusfugit.concurrency.IntermittentTestRunner;
import com.google.code.tempusfugit.concurrency.annotations.Intermittent;
import com.meterware.simplestub.Memento;
import com.meterware.simplestub.StaticStubSupport;
import io.kubernetes.client.models.V1ObjectMeta;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import oracle.kubernetes.TestUtils;
import oracle.kubernetes.weblogic.domain.v2.Domain;
import oracle.kubernetes.weblogic.domain.v2.DomainSpec;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;

@RunWith(IntermittentTestRunner.class)
public class ServerKubernetesObjectsLookupTest {

  private List<Memento> mementos = new ArrayList<>();

  private String retryLegalName;
  private ServerKubernetesObjects retryInstance;

  @Rule
  public TestWatcher watcher =
      new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
          super.failed(e, description);
          System.out.println("Tell Russell\n" + DomainPresenceMonitor.getExplanation());
          Map<String, ServerKubernetesObjects> returnedMap =
              ServerKubernetesObjectsManager.getServerKubernetesObjects();

          System.out.printf(
              "\nObject in map with key %s is %s, which compares %b",
              retryLegalName,
              returnedMap.get(retryLegalName),
              retryInstance == returnedMap.get(retryLegalName));
        }
      };

  @Before
  public void setUp() throws Exception {
    mementos.add(TestUtils.silenceOperatorLogger());
    mementos.add(
        StaticStubSupport.install(
            DomainPresenceInfoManager.class, "domains", new ConcurrentHashMap<>()));
    mementos.add(
        StaticStubSupport.install(
            ServerKubernetesObjectsManager.class, "serverMap", new ConcurrentHashMap<>()));
    ServerKubernetesObjectsManager.clear();
    DomainPresenceMonitor.clear();
  }

  @After
  public void tearDown() {
    for (Memento memento : mementos) memento.revert();
  }

  private Domain createDomain(String uid, String namespace) {
    return new Domain()
        .withSpec(new DomainSpec().withDomainUID(uid))
        .withMetadata(new V1ObjectMeta().namespace(namespace));
  }

  @Test
  public void whenNoPreexistingDomains_createEmptyServerKubernetesObjectsMap() {
    assertThat(ServerKubernetesObjectsManager.getServerKubernetesObjects(), is(anEmptyMap()));
  }

  @Test
  public void whenK8sHasDomainWithOneServer_canLookupFromServerKubernetesObjectsFactory() {
    Domain domain = createDomain("UID1", "ns1");
    DomainPresenceInfo info = DomainPresenceInfoManager.getOrCreate(domain);

    ServerKubernetesObjects sko = ServerKubernetesObjectsManager.getOrCreate(info, "admin");

    assertThat(info.getServers(), hasEntry(equalTo("admin"), sameInstance(sko)));

    retryLegalName = LegalNames.toServerName("UID1", "admin");
    retryInstance = sko;
    assertThat(
        ServerKubernetesObjectsManager.getServerKubernetesObjects(),
        hasEntry(equalTo(LegalNames.toServerName("UID1", "admin")), sameInstance(sko)));
  }

  @Test
  @Intermittent(repetition = 10)
  public void
      whenK8sHasDomainAndServerIsRemoved_canNoLongerLookupFromServerKubernetesObjectsFactory() {
    Domain domain = createDomain("UID1", "ns1");
    DomainPresenceInfo info = DomainPresenceInfoManager.getOrCreate(domain);

    ServerKubernetesObjects sko = ServerKubernetesObjectsManager.getOrCreate(info, "admin");

    info.getServers().remove("admin", sko);

    assertThat(info.getServers(), is(anEmptyMap()));

    assertThat(ServerKubernetesObjectsManager.getServerKubernetesObjects(), is(anEmptyMap()));
  }
}
