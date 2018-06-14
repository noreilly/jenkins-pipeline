#!/usr/bin/groovy
package com.noreilly;

def baseTemplate(body){
    podTemplate(label: 'jenkins-pipeline', idleMinutes: 1440, containers: [
            containerTemplate(name: 'jnlp', image: 'jenkins/jnlp-slave:3.19-1', args: '${computer.jnlpmac} ${computer.name}', workingDir: '/home/jenkins', ttyEnabled: true),
            containerTemplate(name: 'mvn', image: 'maven:3.5.3', command: 'cat', ttyEnabled: true),
            containerTemplate(name: 'helm', image: 'imduffy15/helm-kubectl:2.8.2', command: 'cat', ttyEnabled: true)
    ],
    volumes: [
        hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')
    ])
    {
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
helm repo add shipyard-stable https://storage.googleapis.com/pd-stable-helm-charts
helm dependency build "deploy/"
    '''
}

def helmDryRun() {
    def config = getConfig()

    helmRenderConfig()
    helmLint()

    def args = [
            dry_run    : true,
            name       : config.helm.name,
            namespace  : config.helm.namespace
    ]

    helmDeployRaw(args)

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

        sh "helm upgrade --dry-run --install ${args.name} deploy --namespace=${namespace}"
    } else {
        println "Running deployment"

        sh "helm upgrade --wait --install ${args.name} deploy --namespace=${namespace}"

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
