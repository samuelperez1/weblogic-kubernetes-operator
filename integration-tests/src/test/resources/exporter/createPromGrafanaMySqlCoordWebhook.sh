#!/bin/bash -x
# Copyright (c) 2019, Oracle Corporation and/or its affiliates.  All rights reserved.
# Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upload
monitoringExporterDir=$1
domainNS=$5
domainNS1=$6
resourceExporterDir=$2
promVersionArgs=$3
grafanaVersionArgs=$4
monitoringExporterEndToEndDir=${monitoringExporterDir}/src/samples/kubernetes/end2end


#create database
sed -i "s/default/${domainNS1}/g"  ${monitoringExporterEndToEndDir}/mysql/persistence.yaml
sed -i "s/default/${domainNS1}/g"  ${monitoringExporterEndToEndDir}/mysql/mysql.yaml
sed -i "s/default/${domainNS1}/g"  ${monitoringExporterEndToEndDir}/demo-domains/domainBuilder/scripts/simple-topology.yaml
sed -i "s/3306\/@@PROP:DOMAIN_NAME@@/3306\/domain1/g" ${monitoringExporterEndToEndDir}/demo-domains/domainBuilder/scripts/simple-topology.yaml
cp ${resourceExporterDir}/promvalues.yaml ${monitoringExporterEndToEndDir}/prometheus/promvalues.yaml

sed -i "s/default;domain1/${domainNS};${domainNS}/g" ${monitoringExporterEndToEndDir}/prometheus/promvalues.yaml
kubectl apply -f ${monitoringExporterEndToEndDir}/mysql/persistence.yaml
kubectl apply -f ${monitoringExporterEndToEndDir}/mysql/mysql.yaml

sleep 30

POD_NAME=$(kubectl get pod -l app=mysql -o jsonpath="{.items[0].metadata.name}" -n ${domainNS1} )
kubectl exec -it $POD_NAME -n $domainNS1 -- mysql -p123456 -e "CREATE DATABASE domain1;"
kubectl exec -it $POD_NAME -n $domainNS1 -- mysql -p123456 -e "CREATE USER 'wluser1' IDENTIFIED BY 'wlpwd123';"
kubectl exec -it $POD_NAME -n $domainNS1 -- mysql -p123456 -e "GRANT ALL ON domain1.* TO 'wluser1';"
kubectl exec -it $POD_NAME -n $domainNS1 -- mysql -u wluser1 -pwlpwd123 -D domain1 -e "show tables;"

kubectl create ns monitoring

kubectl apply -f ${monitoringExporterEndToEndDir}/prometheus/persistence.yaml
kubectl apply -f ${monitoringExporterEndToEndDir}/prometheus/alert-persistence.yaml
kubectl get pv -n monitoring
kubectl get pvc -n monitoring

helm repo update
export appname=grafana
for p in `kubectl get po -l app=$appname -o name -n monitoring `;do echo $p; kubectl delete ${p} -n monitoring --force --grace-period=0 --ignore-not-found; done

export appname=prometheus
for p in `kubectl get po -l app=$appname -o name -n monitoring `;do echo $p; kubectl delete ${p} -n monitoring --force --grace-period=0 --ignore-not-found; done

promVersionArgs=9.7.4
helm install --debug --wait --name prometheus --namespace monitoring --values  ${monitoringExporterEndToEndDir}/prometheus/promvalues.yaml stable/prometheus  --version ${promVersionArgs}

helm list --all

POD_NAME=$(kubectl get pod -l app=prometheus -n monitoring -o jsonpath="{.items[0].metadata.name}")
kubectl describe $POD_NAME -n monitoring


kubectl --namespace monitoring create secret generic grafana-secret --from-literal=username=admin --from-literal=password=12345678


kubectl apply -f ${monitoringExporterEndToEndDir}/grafana/persistence.yaml
helm install --wait --name grafana --namespace monitoring --values  ${monitoringExporterEndToEndDir}/grafana/values.yaml stable/grafana --version ${grafanaVersionArgs}

cd ${monitoringExporterEndToEndDir}
docker build ./webhook -t webhook-log:1.0;
docker login $REPO_REGISTRY -u $REPO_USERNAME -p $REPO_PASSWORD
docker tag webhook-log:1.0 phx.ocir.io/weblogick8s/webhook-log:1.0
docker push phx.ocir.io/weblogick8s/webhook-log:1.0
if [ ! "$?" = "0" ] ; then
   echo "Error: Could not push the image to $REPO_REGISTRY".
  #exit 1
fi
echo 'docker list images for webhook'
docker images | grep webhook
kubectl create ns webhook
#sed -i "s/Never/Always/g"  ${monitoringExporterEndToEndDir}/webhook/server.yaml
#sed -i "s/webhook-log:1.0/phx.ocir.io\/weblogick8s\/webhook-log:1.0/g"  ${monitoringExporterEndToEndDir}/webhook/server.yaml
sed -i "s/webhook-log:1.0/phx.ocir.io\/weblogick8s\/webhook-log:1.0/g"  ${resourceExporterDir}/server.yaml
sed -i "s/docker-store/${IMAGE_PULL_SECRET_OPERATOR}/g"  ${resourceExporterDir}/server.yaml
cat ${resourceExporterDir}/server.yaml
kubectl apply -f ${resourceExporterDir}/server.yaml
POD_NAME=$(kubectl get pod -l app=webhook -o jsonpath="{.items[0].metadata.name}" -n webhook )
kubectl describe pods ${POD_NAME} -n webhook
kubectl logs ${POD_NAME} -n webhook
echo "Getting info about webhook"
kubectl get pods -n webhook

#create coordinator
cd ${resourceExporterDir}
cp coordinator.yml coordinator_${domainNS}.yaml
sed -i "s/default/$domainNS/g"  coordinator_${domainNS}.yaml
sed -i "s/config_coordinator/phx.ocir.io\/weblogick8s\/config_coordinator/g"  coordinator_${domainNS}.yaml
sed -i "s/docker-store/${IMAGE_PULL_SECRET_OPERATOR}/g"  coordinator_${domainNS}.yaml
cat ${resourceExporterDir}/coordinator_${domainNS}.yaml
kubectl apply -f ${resourceExporterDir}/coordinator_${domainNS}.yaml
kubectl get pods -n ${domainNS}

echo "Run the script [createPromGrafanaMySqlCoordWebhook.sh] ..."
