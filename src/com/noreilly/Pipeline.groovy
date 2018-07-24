#!/usr/bin/groovy
package com.noreilly;

def baseTemplate(body){
    podTemplate(label: 'jenkins-pipeline', idleMinutes: 1440, containers: [
            containerTemplate(name: 'jnlp', image: 'jenkins/jnlp-slave:3.19-1', args: '${computer.jnlpmac} ${computer.name}', workingDir: '/home/jenkins', ttyEnabled: true),
            containerTemplate(name: 'mvn', image: 'maven:3.5.3', command: 'cat', ttyEnabled: true),
            containerTemplate(name: 'node', image: 'imduffy15/docker-frontend:0.0.1', command: 'cat', ttyEnabled: true),
            containerTemplate(name: 'docker', image: 'imduffy15/docker-gcloud:0.0.1', command: 'cat', ttyEnabled: true),
            containerTemplate(name: 'helm', image: 'imduffy15/helm-kubectl:3.0.0', command: 'cat', ttyEnabled: true)
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

def helmLint(chartName) {
    sh "helm lint charts/${chartName}"
}

def helmRenderConfig(String chartName) {
    env.CHART_NAME = chartName
    sh '''
helm init
helm version

find "charts/${CHART_NAME}/" -type f -name "*.template" | while IFS= read -r template; do
    output="${template%.*}"
    sigil -f "${template}" IMAGE_TAG="${IMAGE_TAG}" IMAGE_REPO="${IMAGE_REPO}" > "${output}"
done
echo "Printing rendered Helm Values"
cat charts/${CHART_NAME}/*values.yaml
helm repo add shipyard-stable https://storage.googleapis.com/pd-stable-helm-charts
helm repo add brigade https://azure.github.io/brigade
helm repo add kubernetes-charts http://storage.googleapis.com/kubernetes-charts 
rm -f charts/${CHART_NAME}/requirements.lock
helm dependency build "charts/${CHART_NAME}/"
    '''
}

def helmPublishChart(environment) {
    def config = getConfig()
    switchKubeContext(environment)
    helmRenderConfig(config.helm.name)
    helmLint(config.helm.name)


    def args = [
            dry_run    : true,
            name       : config.helm.name,
            namespace  : config.helm.namespace
    ]

    helmDeployRaw(args, environment)

    publishHelmCharts()

}

def switchKubeContext(String environment){
	if(environment == null){
	    throw new RuntimeException("Please select an environment to deloy to. Prod or test")	
	}
	if( env.CLOUD_TYPE == "GKE"){
	     String clusterName	
 	     String clusterZone
             if(environment == "prod"){ 
		   clusterName = env.CLOUD_PROD_CLUSTER_NAME  
		   clusterZone = env.CLOUD_PROD_CLUSTER_ZONE  
	     } else if(environment == "test"){
		   clusterName = env.CLOUD_TEST_CLUSTER_NAME  
		   clusterZone = env.CLOUD_TEST_CLUSTER_ZONE   
	     }	
	     if(clusterName == null || clusterZone == null){
		     throw new RuntimeException("Environment ${environment} is not set up. This should be configured through jenkins variables. CLOUD_PROD_CLUSTER_NAME, CLOUD_PROD_CLUSTER_ZONE, CLOUD_TEST_CLUSTER_NAME, CLOUD_TEST_CLUSTER_ZONE")	     
	     }
		
	     sh """		   
		     gcloud container clusters get-credentials ${clusterName}  --zone ${clusterZone}
		     kubectl get pods
	     """
	   
	}

}

def getConfig() {
    def inputFile = readFile('Jenkinsfile.json')
    def config = new groovy.json.JsonSlurperClassic().parseText(inputFile)
    println(config)
    return config
}

def helmDeploy(String environment) {
    def config = getConfig()
    switchKubeContext(environment)	

//    helmLint(config.helm.name)

    def args = [
            dry_run    : false,
            name       : config.helm.name,
            namespace  : config.helm.namespace
    ]

    helmDeployRaw(args, environment)
}

// must run dry run first
def helmDeployRaw(Map args, String environment) {
//    helmRenderConfig(args.name)

    if (args.namespace == null) {
        namespace = "default"
    } else {
        namespace = args.namespace
    }

    if (args.dry_run) {
        println "Running dry-run deployment"

        sh "helm upgrade --dry-run --install ${args.name} ./charts/${args.name} --namespace=${namespace} -f charts/${args.name}/${environment}.values.yaml"
    } else {
        println "Running deployment"

	    sh "helm upgrade --wait --install ${args.name} ./charts/${args.name} --namespace=${namespace} -f charts/${args.name}/${environment}.values.yaml"

	sh """
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



def publishHelmCharts(){
    def config = getConfig()
    println("Config ${config.helm.name}")
//    helmRenderConfig(config.helm.name)
//    helmLint(config.helm.name)

    if( env.CLOUD_TYPE == "GKE"){
        publishHelmChartsGcloud()
    }

}
def publishHelmChartsGcloud(){
    sh '''
    mkdir -p helm-target
    cd helm-target
    gsutil cp gs://pd-stable-helm-charts/index.yaml .
    helm dependency build ../charts/email-service
    helm package ../charts/email-service   
    helm repo index --url https://storage.googleapis.com/sy-app-charts --merge ./index.yaml .
    gsutil -m rsync ./ gs://sy-app-charts/
    cd ..
    ls -l ${STABLE_REPO_DIR}
'''
}


return this
