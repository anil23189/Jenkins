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
        }

        environment{
            ansible_branch = "ap-digest-signing"
            settings_id = "${pipelineParams.settings_id}"
            git_credentials = credentials('zapp.jenkins.build')
            ORACLE_HOME = "/fs01/app/oracle/product/Client11g"
            PATH = "$PATH:$ORACLE_HOME/bin"
            db_test_dir = "${WORKSPACE}/properties/test"
            component = "ap-digest-signing"
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
            stage('Prepare Ansible') {
                steps {
                    script {
                        mw_util = utils.utility_version(params.deploy_util_version, 'deploy_util_version')
                        ap_digest_signing_version = utils.version(component)
                    }
                    sh "mkdir -p ansible-${ansible_branch}"
                    dir ("ansible-${ansible_branch}") {
                        git branch: "${ansible_branch}", credentialsId: 'zapp.jenkins.build', url: "http://bitbucket.vocalink.co.uk/scm/zapp/ansible.git"
                        sh "ansible-galaxy install --force -r requirements.yml -p roles/"
                        sh """
                    ansible-playbook -i inv/hosts.yml ap_digest_signing.yml -e env=${pipelineParams.env} -c local -e workspace=$WORKSPACE \
                    -e nexus_user=${git_credentials_usr} -e nexus_pass=${git_credentials_psw} \
                    -e ap_digest_signing_version=${ap_digest_signing_version} -e nexus_download="false" -e download_artifacts=false \
                    """
                    }
                }
            }

            stage('Lock CB') {
                options {
                    lock(resource: 'digest-signing-db')
                }
                stages {

                    stage('Unit Tests') {
                        steps {
                            dir ("${component}"){
                                script {
                                    utils.mvn(pipelineParams.test_goal, settings_id)
                                }
                            }
                        }
                    }

                    stage('Deploy App'){
                        steps {
                            dir ('ap-middleware-deployment-utility') {
                                sh """
                                ./runAPUtil.sh -Dconfig_properties_url=$WORKSPACE/properties/config-ap-deploy.properties
                                """
                            }
                        }
                    }
                    stage('Integration Tests') {
                        steps {
                            dir ("${component}") {
                                script {
                                    utils.mvn(pipelineParams.it_test_goal, settings_id)
                                }
                            }
                        }
                    }
                }
            }




            stage('Run Sonar') {
                tools {
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

            stage('Deploy to Nexus') {
                when {
                   // branch "${pipelineParams.branch}"
                   branch 'NEVER'
                }
                steps {
                    dir ("${component}"){
                        script {
                            utils.mvn("clean deploy -Pdev-test-env-v3 -DskipTests", settings_id)
                        }
                    }
                }
            }

        }
    }
}
