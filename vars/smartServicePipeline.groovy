#!/usr/bin/env groovy

def call(validateAndDeployUi) {
    def pipeline = new com.noreilly.Pipeline()

    pipeline.baseTemplate {
        node('jenkins-pipeline') {
            sh "git config --global http.sslVerify false"

            checkout scm
            stage('Compile') {
                container('mvn') {
                    sh '''
export HOST_ADDRESS=$(ip route | grep "default" | awk '{print $3}')
mvn clean compile
'''
                }
            }
            stage('Test') {
                container('mvn') {
                    sh '''
export HOST_ADDRESS=$(ip route | grep "default" | awk '{print $3}')
mvn clean test
'''
                }
            }
            if (!env.gitlabMergeRequestId) {
                stage('Build client') {
                    container('mvn') {

                        def pom = readMavenPom file: 'pom.xml'
                        env.IMAGE_TAG = "${pom.version}-${env.BUILD_NUMBER}"
                        sh '''
export HOST_ADDRESS=$(ip route | grep "default" | awk '{print $3}')
cd client-api
mvn versions:set -DnewVersion=${IMAGE_TAG}
mvn clean deploy -P prod -DskipTests=true
                    '''
                    }
                }

                stage('Build Image') {
                    container('mvn') {
                        def pom = readMavenPom file: 'pom.xml'
                        env.IMAGE_REPO = pom.properties.getProperty("docker.image_repository")
                        env.IMAGE_TAG = "${pom.version}-${env.BUILD_NUMBER}"
                        sh '''
export HOST_ADDRESS=$(ip route | grep "default" | awk '{print $3}')
mvn versions:set -DnewVersion=${IMAGE_TAG}
mvn deploy -P prod,api -DskipTests=true
                    '''
                    }
                }
                stage('Test And Publish Helm Chart') {
                    container('helm') {
                        pipeline.helmPublishChart("prod")
                    }
                }

                stage('Deploy To Prod') {
                    container('helm') {
                        pipeline.helmDeploy("prod")
                    }
                }

                stage('Publish documentation') {
                    container('helm') {
                        pipeline.publishDocumentation()
                    }
                }

                if(validateAndDeployUi) {
                    stage('Publish ui') {
                        container('helm') {
                            pipeline.publishUi()
                        }
                    }
                }
            }
        }
    }

}
