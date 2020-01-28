// Copyright (c) 2019, Oracle Corporation and/or its affiliates.  All rights reserved.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.utils;

import java.util.Map;
import java.util.logging.Level;

import oracle.kubernetes.operator.BaseTest;

/**
 * JRF Domain class with all the utility methods.
 */
public class FmwDomain extends Domain {

  /**
   * FmwDomain constructor.
   *
   * @param inputYaml - jrf domain input yaml file, which should contain the properties used for jrf
   *     domain creation
   * @throws Exception - if any error occurs
   */
  public FmwDomain(String inputYaml) throws Exception {
    // read input domain yaml to test
    this(TestUtils.loadYaml(inputYaml));
  }

  /**
   * FmwDomain constructor.
   *
   * @param inputDomainMap - jrf domain input properties map, which should contain the properties
   *     used for domain creation
   * @throws Exception - if any error occurs
   */
  public FmwDomain(Map<String, Object> inputDomainMap) throws Exception {
    this(inputDomainMap, false);
  }

  public FmwDomain(Map<String, Object> inputDomainMap, boolean adminPortEnabled) throws Exception {
    initialize(inputDomainMap);
    updateDomainMapForJrf(adminPortEnabled);
    createPv();
    createSecret();
    createRcuSecret();
    generateInputYaml();
    callCreateDomainScript(userProjectsDir);
    createLoadBalancer();
  }

  /**
   * update the domainMap with jrf specific information.
   *
   * @param adminPortEnabled - whether the adminPortEnabled, value true or false
   * @throws Exception - if any error occurs
   */
  private void updateDomainMapForJrf(boolean adminPortEnabled) throws Exception {
    // jrf specific input parameter
    domainMap.put(
        "image",
        DbUtils.DEFAULT_FMWINFRA_DOCKER_IMAGENAME + ":" + DbUtils.DEFAULT_FMWINFRA_DOCKER_IMAGETAG);

    if (System.getenv("IMAGE_PULL_SECRET_FMWINFRA") != null) {
      domainMap.put("imagePullSecretName", System.getenv("IMAGE_PULL_SECRET_FMWINFRA"));
    } else {
      domainMap.put("imagePullSecretName", "docker-store");
    }

    // update create-domain-script.sh if adminPortEnabled is true
    if (adminPortEnabled) {
      String createDomainScript =
          domainMap.get("resultDir")
              + "/samples/scripts/create-fmw-infrastructure-domain/domain-home-on-pv/wlst/create-domain-script.sh";
      TestUtils.replaceStringInFile(
          createDomainScript,
          "-managedNameBase ",
          "-adminPortEnabled true -administrationPort 9002 -managedNameBase ");
    }
  }

  /**
   * create rcu secret.
   *
   * @throws Exception - if any error occurs
   */
  private void createRcuSecret() throws Exception {
    RcuSecret rucSecret =
        new RcuSecret(
            domainNS,
            domainMap.getOrDefault("secretName", domainUid + "-rcu-credentials").toString(),
            DbUtils.DEFAULT_RCU_SCHEMA_USERNAME,
            DbUtils.DEFAULT_RCU_SCHEMA_PASSWORD,
            DbUtils.DEFAULT_RCU_SYS_USERNAME,
            DbUtils.DEFAULT_RCU_SYS_PASSWORD);
    domainMap.put("rcuCredentialsSecret", rucSecret.getSecretName());
    final String labelCmd =
        String.format(
            "kubectl label secret %s -n %s weblogic.domainUID=%s weblogic.domainName=%s",
            rucSecret.getSecretName(), domainNS, domainUid, domainUid);
    LoggerHelper.getLocal().log(Level.INFO, "running command " + labelCmd);
    TestUtils.exec(labelCmd);
  }
}