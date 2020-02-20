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
            string(name: 'ap_batch_version', defaultValue: '', description: 'set to deploy from nexus')
            string(name: 'branch', defaultValue: null, description: 'set to deploy from branch')
            string(name: 'commit', defaultValue: null, description: '')
            string(name: 'create_jms', defaultValue: 'false', description: 'create jms queues?')
        }

        environment{
        ansible_branch = "ap-batch-core"
        settings_id = "pwba-settings"
        git_credentials = credentials('zapp.jenkins.build')
        ORACLE_HOME = "/fs01/app/oracle/product/Client11g"
        PATH = "$PATH:$ORACLE_HOME/bin"
        db_test_dir = "${WORKSPACE}/properties/test"
        component = "ap-batch-core"
        }

        agent {
            label 'zapp-dev-env'
        }

        options {
            buildDiscarder(logRotator(numToKeepStr: '10'))
            skipDefaultCheckout(true)
            disableConcurrentBuilds()

        }

        tools {
            maven "${pipelineParams.maven_v}"
            jdk "${pipelineParams.java_v}"
        }

        stages {

            stage('cleanup'){
                steps {
                    deleteDir()
                }
            }

            stage('Build') {
                when {
                    expression { return params.branch }
                }
                steps {
                    sh "mkdir ${component}"
                    dir ("${component}"){
                        git branch: "${params.branch}", credentialsId: 'zapp.jenkins.build', url: "http://bitbucket.vocalink.co.uk/scm/zapp/ap-batch-core.git"
                        script {
                            if (params.commit) {
                                sh "git checkout ${params.commit}"
                                currentBuild.description = "Deploy Ap Batch Core ${params.commit}"
                            }
                            else {
                                currentBuild.description = "Deploy Ap Batch Core ${params.branch}"
                            }
                            utils.mvn("clean install -Pall,dev-env-v3,jenkins -DskipTests", settings_id)
                        }
                    }
                }
            }

            stage('Prepare Ansible') {
                steps {
                    script {
                        mw_util = utils.utility_version(params.deploy_util_version, 'deploy_util_version')
                        db_util = utils.utility_version(params.db_deploy_util_version, 'ap_database_deployment_utility_version')
                        if(!params.branch) {
                            nexus = "-e nexus_download='true' -e ap_batch_version=${params.ap_batch_version}"
                            currentBuild.description = "Deploy Ap Batch Core ${params.ap_batch_version}"
                            
                        }
                        else {
                            ap_batch_version = utils.version(component)
                            nexus = "-e nexus_download='false' -e ap_batch_version=${ap_batch_version}"
                        }
                    }
                    sh "mkdir -p ansible-${ansible_branch}"
                    dir ("ansible-${ansible_branch}") {
                        git branch: "${ansible_branch}", credentialsId: 'zapp.jenkins.build', url: "http://bitbucket.vocalink.co.uk/scm/zapp/ansible.git"
                        sh "ansible-galaxy install --force -r requirements.yml -p roles/"
                        sh """
                    ansible-playbook -i inv/hosts.yml ap-batch.yml -e env=${pipelineParams.env} -c local -e workspace=$WORKSPACE \
                    -e nexus_user=${git_credentials_usr} -e nexus_pass=${git_credentials_psw} -e download_artifacts=false \
                    $db_util $mw_util $nexus -e jms_creation=${params.create_jms}
                    """
                    }
                }
            }

            stage('Deploy DB ') {
                steps {
                    sh "mkdir -p /tmp/${pipelineParams.env}"
                    dir ('ap-database-deployment-utility') {
                        sh "chmod +x ap-db-deploy-bamboo.sh"
                        sh """
                        ./ap-db-deploy-bamboo.sh -DuserConfigFile=$WORKSPACE/properties/app-user-config.properties -DconfigLocation=$WORKSPACE/properties/
                        """
                    }
                }
            }

            stage('Deploy apps ') {
                steps {
                    script{
                        if(params.create_jms == "true") {
                            jms = "-Djms_properties_url_1=$WORKSPACE/properties/jms_server1.properties -Djms_properties_url_2=$WORKSPACE/properties/jms_server2.properties"
                        }
                        else if(params.create_jms == "false"){
                            jms = ""
                        }

                        
                    }
                    dir ('ap-middleware-deployment-utility') {
                        sh """
                        ./runAPUtil.sh -Dconfig_properties_url=$WORKSPACE/properties/config-ap-deploy.properties ${jms}
                        """
                    }
                }
            }
        }
    }        
}
