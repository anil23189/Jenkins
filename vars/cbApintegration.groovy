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
            component = "ap-integration"
            ansible_branch = "ap-integration"
            MAVEN_OPTS="-Xmx4g -Xms4g -XX:+UseCompressedOops -XX:MaxPermSize=512m -XX:+UseConcMarkSweepGC -XX:-UseGCOverheadLimit"
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
                        if (!params.deploy_util_version?.trim()) {
                            deploy_util = ""
                        }
                        else {
                            deploy_util = "-e deploy_util_version=${params.deploy_util_version}"
                        }
                        ap_integration_version = utils.version(component)
                    }
                    sh "mkdir -p ansible-${ansible_branch}"
                    dir ("ansible-${ansible_branch}") {
                        git branch: "${ansible_branch}", credentialsId: 'zapp.jenkins.build', url: "http://bitbucket.vocalink.co.uk/scm/zapp/ansible.git"
                        sh "ansible-galaxy install --force -r requirements.yml -p roles/"
                        sh """
                        ansible-playbook -i inv/hosts.yml ap-integration.yml -e env=${pipelineParams.env} -c local -e workspace=$WORKSPACE \
                        -e nexus_user=${git_credentials_usr} -e nexus_pass=${git_credentials_psw} ${deploy_util} \
                        -e ap_integration_version=${ap_integration_version} -e download_artifacts=false -e nexus_download=false
                        """
                    }
                }
            }
			
			 stage('Lock CB') {
                options {
                    lock(resource: 'ap-integration-cb')
                }
                stages {
                    stage('Deploy App to CB') {
                        when {
                            anyOf { branch "${pipelineParams.branch}"; branch 'PR*'}
                            //changeRequest target: "${pipelineParams.target}"
                        }
                        steps {
                            dir ('ap-middleware-deployment-utility') {
                                sh "./runAPUtil.sh -Dconfig_properties_url=$WORKSPACE/properties/config-ap-deploy.properties"
                            }
                        }
                    }

                    stage('Integration Tests') {
                        when {
                            anyOf { branch "${pipelineParams.branch}"; branch 'PR*'}
                            //changeRequest target: "${pipelineParams.target}"
                       }
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
                            utils.mvn("clean deploy -DskipTests -Dweblogic.home=/home/devops/fmw/wlserver_10.3 -Dosb.home=/home/devops/fmw/osb", settings_id)
                        }
                    }
                }
            }
        }
    }        
}
