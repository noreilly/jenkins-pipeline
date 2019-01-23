#!/usr/bin/groovy
package com.noreilly

def baseTemplate(body) {
    properties([
        buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '14', numToKeepStr: '')),
        disableConcurrentBuilds()

    ])
    podTemplate(label: 'jenkins-pipeline', idleMinutes: 1440, containers: [
        containerTemplate(name: 'jnlp', image: 'imduffy15/jnlp-slave:3.27-1-alpine-2', args: '${computer.jnlpmac} ${computer.name}', workingDir: '/home/jenkins', ttyEnabled: true),
        containerTemplate(name: 'mvn', image: 'imduffy15/docker-java:0.0.4', command: 'cat', ttyEnabled: true),
        containerTemplate(name: 'golang', image: 'imduffy15/docker-golang:0.0.3', command: 'cat', ttyEnabled: true),
        containerTemplate(name: 'node', image: 'imduffy15/docker-frontend:0.0.2', command: 'cat', ttyEnabled: true),
        containerTemplate(name: 'docker', image: 'imduffy15/docker-gcloud:0.0.3', command: 'cat', ttyEnabled: true),
        containerTemplate(name: 'helm', image: 'imduffy15/helm-kubectl:3.0.1', command: 'cat', ttyEnabled: true),
        containerTemplate(name: 'android', image: 'imduffy15/docker-android:0.0.5', command: 'cat', ttyEnabled: true)
    ],
        volumes: [
            hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')
        ]) {
        body()
    }
}

def kubectlTestConnectivity() {
    sh """#!/bin/bash
kubectl get nodes > /dev/null"""
}

def helmLint(chartName) {
    sh """#!/bin/bash
helm lint charts/${chartName}"""
}

def helmRenderConfig(String chartName) {
    env.CHART_NAME = chartName
    sh '''#!/bin/bash
helm init
helm version

echo "apiVersion: v1" > charts/${CHART_NAME}/Chart.yaml
echo "name: $CHART_NAME" >> charts/${CHART_NAME}/Chart.yaml
echo "version: ${IMAGE_TAG}" >> charts/${CHART_NAME}/Chart.yaml

find "charts/${CHART_NAME}/" -type f -name "*.template" | while IFS= read -r template; do
    output="${template%.*}"
    sigil -f "${template}" IMAGE_TAG="${IMAGE_TAG}" IMAGE_REPO="${IMAGE_REPO}" > "${output}"
done
echo "Printing rendered Helm Values"
cat charts/${CHART_NAME}/*values.yaml
helm repo add shipyard-stable https://storage.googleapis.com/pd-stable-helm-charts
helm repo add brigade https://azure.github.io/brigade
helm repo add kubernetes-charts http://storage.googleapis.com/kubernetes-charts 
helm repo add $HELM_CHARTS_REPOSITORY https://storage.googleapis.com/$HELM_CHARTS_REPOSITORY
rm -f charts/${CHART_NAME}/requirements.lock
helm dependency build "charts/${CHART_NAME}/"
    '''
}

def helmPublishChart(environment) {
    withKubeContext(environment) {
        def config = getConfig()

        helmRenderConfig(config.helm.name)
        helmLint(config.helm.name)


        def args = [
            dry_run  : true,
            name     : config.helm.name,
            namespace: config.helm.namespace
        ]
        // Having issue publishing chart on Jenkins
        // The resulting published artefact is not subsequently used, so commeting out
        publishHelmCharts()
        helmDeployRaw(args, environment)
    }
}

def withKubeContext(environment, closure) {
    if (environment == null) {
        throw new RuntimeException("Please select an environment to deploy to. production or test")
    }

    if (environment == "prod") {
        withCredentials([[$class: 'FileBinding', credentialsId: "production-kube-config.yaml", variable: 'KUBECONFIG']]) {
            closure()
        }
    } else if (environment == "test") {
        withCredentials([[$class: 'FileBinding', credentialsId: "test-kube-config.yaml", variable: 'KUBECONFIG']]) {
            closure()
        }
    } else {
        throw new RuntimeException("No such environment ")
    }
}

def getConfig() {
    def inputFile = readFile('Jenkinsfile.json')
    def config = new groovy.json.JsonSlurperClassic().parseText(inputFile)
    println(config)
    return config
}

def helmDeploy(String environment) {
    withKubeContext(environment) {

        def config = getConfig()

        def args = [
            dry_run  : false,
            name     : config.helm.name,
            namespace: config.helm.namespace
        ]

        helmDeployRaw(args, environment)
    }
}

// must run dry run first
def helmDeployRaw(Map args, String environment) {
    if (args.namespace == null) {
        namespace = "default"
    } else {
        namespace = args.namespace
    }

    if (args.dry_run) {
        println "Running dry-run deployment"

        sh "helm upgrade --debug --dry-run --install ${args.name} ./charts/${args.name} --namespace=${namespace} -f charts/${args.name}/${environment}.values.yaml"
    } else {
        println "Running deployment"

        sh "helm upgrade --debug  --wait --install ${args.name} ./charts/${args.name} --namespace=${namespace} -f charts/${args.name}/${environment}.values.yaml"

        sh """#!/bin/bash
hosts="\$(kubectl get ingress -l "release=${args.name}" -o json | jq -r "select(.items[0] != null) | .items[0].spec.rules[] | .host")"

for host in \${hosts}; do
  echo "Attempting to resolve \${host}..."
  until host \${host};
  do
    echo "Waiting for \${host} to resolve..."
    sleep 30
  done;
done
  """

        echo "Application ${args.name} successfully deployed. Use helm status ${args.name} to check"
    }
}

def helmDelete(Map args) {
    println "Running helm delete ${args.name}"

    sh "helm delete ${args.name}"
}

def publishHelmCharts() {
    def config = getConfig()
    println("Config ${config.helm.name}")

    publishHelmCharts(config.helm.name)
}

def publishHelmCharts(chartName) {
    env.CHART_NAME = chartName
    sh '''
#!/bin/bash
mkdir -p helm-target
cd helm-target
#gsutil cp gs://$HELM_CHARTS_REPOSITORY/index.yaml .
helm dependency build ../charts/$CHART_NAME
#helm package ../charts/$CHART_NAME   
#helm repo index --url https://storage.googleapis.com/$HELM_CHARTS_REPOSITORY --merge ./index.yaml .
#gsutil -m rsync ./ gs://$HELM_CHARTS_REPOSITORY/
cd ..
#ls -l ${STABLE_REPO_DIR}
'''
}

def mavenDockerPublish() {
    def pom = readMavenPom file: 'pom.xml'
    env.IMAGE_REPO = pom.properties.getProperty("docker.image_repository")
    env.IMAGE_TAG = "${pom.version}-${env.BUILD_NUMBER}"
    sh '''#!/bin/bash
mvn versions:set -DnewVersion=${IMAGE_TAG}
mvn deploy -DskipTests=true'''
}

return this
