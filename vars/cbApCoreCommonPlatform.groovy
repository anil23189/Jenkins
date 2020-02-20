import com.zapp.utilities

def call(body) {
    utils = new utilities()
    def pipelineParams= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    pipeline {

        parameters{
            string(name: 'deploy_util_version', defaultValue: '', description: 'leave blank to take version from ansible vars')
            string(name: 'db_deploy_util_version', defaultValue: '', description: 'leave blank to take version from ansible vars')
        }

        environment{
            settings_id = "${pipelineParams.settings_id}"
            git_credentials = credentials('zapp.jenkins.build')
            component = "ap-core-platform"
            jenkins_sonar_token = credentials('jenkins.sonarqube.token')
        }

        agent {
            label 'zapp-dev-env'
        }

        options {
            buildDiscarder(logRotator(numToKeepStr: '4'))
            skipDefaultCheckout(true)
            disableConcurrentBuilds()

        }

        tools {
            maven "${pipelineParams.maven_v}"
            jdk "${pipelineParams.java_v}"
        }

        stages {
            stage('Checkout') {
                steps {
                    deleteDir()
                    sh "mkdir ${component}"
                    dir ("${component}"){
                        checkout scm
                    }
                    script{
                        currentBuild.description = ""
                    }
                }
            }

            stage('Build') {
                when {
                    anyOf { branch "${pipelineParams.branch}"; branch 'PR*'}
                }

                steps {
                    dir ("${component}"){
                        script {
                            utils.mvn(pipelineParams.build_goal, settings_id)
                        }
                    }

                }
            }
            stage('Test') {
                steps {
                    dir ("${component}") {
                        script {
                            utils.mvn(pipelineParams.test_goal, settings_id)
                        }
                    }
                }

            }

            stage('SonarQube') {
                tools {
                    maven 'Maven 3.0.4'
                    jdk 'jdk1.8_192'
                }
                steps {
                    dir ("${component}") {
                        script {
                            withSonarQubeEnv("SonarQube") {
                                utils.mvn(pipelineParams.sonar_goal, settings_id)
                            }
                        }
                    }
                }
            }

         //  stage('Deploy to Nexus') {

            //    steps {
                 //   dir ("${component}"){
                     //   script {
                         //   utils.mvn(pipelineParams.nexus_goal, settings_id)
                      //  }
                  //  }
              //  }
           // }



        }
        post {
            success {
                junit testResults: '**/target/surefire-reports/*.xml', allowEmptyResults: true
            }

        }
    }
}
