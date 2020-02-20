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
            settings_id = "${pipelineParams.settings_id}"
            git_credentials = credentials('zapp.jenkins.build')
            component = "ap-p2m-stubs"
            ansible_branch = "p2m-stubs"
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

            stage('Unit Tests') {
                steps {
                    dir ("${component}"){
                        script {
                            utils.mvn(pipelineParams.test_goal, settings_id)
                        }
                    }
                }
            }

            stage('Prepare Ansible') {
                
                steps {
                    script {
                        if (!params.deploy_util_version?.trim()) {
                            deploy_util = ""
                        }
                        else {
                            deploy_util = "-e deploy_util_version=${params.deploy_util_version}"
                        
                        }
                        ap_p2m_stubs_version = utils.version(component)
                    }
                    sh "mkdir -p ansible-${ansible_branch}"
                    dir ("ansible-${ansible_branch}") {
                        git branch: "${ansible_branch}", credentialsId: 'zapp.jenkins.build', url: "http://bitbucket.vocalink.co.uk/scm/zapp/ansible.git"
                        sh "ansible-galaxy install --force -r requirements.yml -p roles/"
                        sh """
                        ansible-playbook -i inv/hosts.yml ap_p2m_stubs.yml -e env=${pipelineParams.env} -c local -e workspace=$WORKSPACE \
                        -e nexus_user=${git_credentials_usr} -e nexus_pass=${git_credentials_psw} ${deploy_util} \
                        -e ap_p2m_stubs_version=${ap_p2m_stubs_version} -e download_artifacts=false -e nexus_download=false
                        """
                    }
                }
            }
              stage('Lock CB') {
                options {
                    lock(resource: 'dv223_apservices')
                }
                stages {
                    stage('Deploy App to CB') {
                        when {
                            branch 'PR*'
                            changeRequest target: "${pipelineParams.target}"
                        }
                        steps {
                           dir ('ap-middleware-deployment-utility') {
                                sh "./runAPUtil.sh -Dconfig_properties_url=$WORKSPACE/properties/config-stubs.properties"
					 
                            }
                        }
                    }
          

                    stage('Integration Tests') {
                        
                        steps {
                            dir ("${component}"){
                                script {
                                    utils.mvn(pipelineParams.it_test_goal, settings_id)
                                }
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
                            utils.mvn("clean deploy -DskipTests", settings_id)
                        }
                    }
                }
            }

            stage('Publish test Results') {
                when {
                    branch 'PR*'
                 
                }
                steps {
                    dir ("${component}"){
                        junit '**/target/surefire-reports/*.xml'
                    }
                }
            }
        }
    }        
}