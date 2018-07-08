#!/usr/bin/groovy
package com.noreilly;

def baseTemplate(body){
    podTemplate(label: 'jenkins-pipeline', idleMinutes: 1440, containers: [
            containerTemplate(name: 'jnlp', image: 'jenkins/jnlp-slave:3.19-1', args: '${computer.jnlpmac} ${computer.name}', workingDir: '/home/jenkins', ttyEnabled: true),
            containerTemplate(name: 'mvn', image: 'maven:3.5.3', command: 'cat', ttyEnabled: true),
            containerTemplate(name: 'node', image: 'imduffy15/docker-frontend:0.0.1', command: 'cat', ttyEnabled: true),
            containerTemplate(name: 'docker', image: 'imduffy15/docker-gcloud:0.0.1', command: 'cat', ttyEnabled: true),
            containerTemplate(name: 'helm', image: 'gcr.io/portdynamics/gcloud-helm:0.2', command: 'cat', ttyEnabled: true)
    ],
    volumes: [
        hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')
    ]){
        body()
    }
}

def kubectlTestConnectivity() {
    sh "kubectl get nodes > /dev/null"
}

def helmLint() {
    sh "helm lint deploy"
}

def helmRenderConfig() {
    sh '''
helm init
helm version
env
find "deploy/" -type f -name "*.template" | while IFS= read -r template; do
    output="${template%.*}"
    sigil -f "${template}" IMAGE_TAG="${IMAGE_TAG}" IMAGE_REPO="${IMAGE_REPO}" > "${output}"
done
echo "Printing rendered Helm Values"
cat deploy/values.yaml
helm repo add shipyard-stable https://storage.googleapis.com/pd-stable-helm-charts
helm repo add brigade https://azure.github.io/brigade
helm repo add kubernetes-charts http://storage.googleapis.com/kubernetes-charts 
rm -f deploy/requirements.lock
helm dependency build "deploy/"
    '''
}

def helmDryRun() {
    def config = getConfig()
    switchKubeContext()
    helmRenderConfig()
    helmLint()

    def args = [
            dry_run    : true,
            name       : config.helm.name,
            namespace  : config.helm.namespace
    ]

    helmDeployRaw(args)

}

def switchKubeContext(){
	if( env.CLOUD_TYPE == "GKE"){
	     sh """
		     echo "$CLOUD_CREDENTIALS" > /tmp/creds.json;
		     gcloud auth activate-service-account --key-file /tmp/creds.json;
		     gcloud container clusters get-credentials $GKE_CLUSTER  --zone $GKE_ZONE
	     """
	   
	}

}

def getConfig() {
    def inputFile = readFile('Jenkinsfile.json')
    def config = new groovy.json.JsonSlurperClassic().parseText(inputFile)
    println(config)
    return config
}

def helmDeploy() {
    def config = getConfig()

    helmLint()

    def args = [
            dry_run    : false,
            name       : config.helm.name,
            namespace  : config.helm.namespace
    ]

    helmDeployRaw(args)
}

def helmDeployRaw(Map args) {
    helmRenderConfig()

    if (args.namespace == null) {
        namespace = "default"
    } else {
        namespace = args.namespace
    }

    if (args.dry_run) {
        println "Running dry-run deployment"

        sh "helm upgrade --dry-run --install ${args.name} deploy --namespace=${namespace} -f deploy/values.yaml"
    } else {
        println "Running deployment"

        sh "helm upgrade --wait --install ${args.name} deploy --namespace=${namespace} -f deploy/values.yaml"

        echo "Application ${args.name} successfully deployed. Use helm status ${args.name} to check"
    }
}

def helmDelete(Map args) {
    println "Running helm delete ${args.name}"

    sh "helm delete ${args.name}"
}

@NonCPS
def getMapValues(Map map = [:]) {
    // jenkins and workflow restriction force this function instead of map.values(): https://issues.jenkins-ci.org/browse/JENKINS-27421
    def entries = []
    def map_values = []

    entries.addAll(map.entrySet())

    for (int i = 0; i < entries.size(); i++) {
        String value = entries.get(i).value
        map_values.add(value)
    }

    return map_values
}

return this
