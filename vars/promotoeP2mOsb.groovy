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
            string(name: 'ap_p2m_integration_version', defaultValue: '', description: 'ap-p2m-integration-version')
            string(name: 'branch', defaultValue: null, description: 'set to deploy from branch')
            string(name: 'commit', defaultValue: null, description: '')
            
        }

        environment{
            git_credentials = credentials('zapp.jenkins.build')
            ansible_branch = "ap-p2m-integration"
            component = "ap-p2m-integration"
            settings_id = "pwba-settings"
            MAVEN_OPTS="-Xmx4g -Xms4g -XX:+UseCompressedOops -XX:MaxPermSize=512m -XX:+UseConcMarkSweepGC -XX:-UseGCOverheadLimit"
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
                        git branch: "${params.branch}", credentialsId: 'zapp.jenkins.build', url: "http://bitbucket.vocalink.co.uk/scm/zapp/ap-p2m-integration.git"
                        script {
                            if (params.commit) {
                                sh "git checkout ${params.commit}"
                                currentBuild.description = "Deploy P2mIntegration ${params.commit}"
                            }
                            else {
                                currentBuild.description = "Deploy P2mIntegration ${params.branch}"
                            }
                            utils.mvn("clean install -e -Pdev-test-env-v3-digital,update-dev-timeout -Dsun.lang.ClassLoader.allowArraySyntax=true -Dbuild-test-stub -DskipTests -Dweblogic.home=/home/devops/fmw/wlserver_10.3 -Dosb.home=/home/devops/fmw/osb", settings_id)
                        }
                    }
                }
            }
            stage('Prepare Ansible') {
                steps {
                    script {
                        if(!params.branch){
                            currentBuild.description = "Deploy P2mIntegration ${params.ap_p2p_integration_version}"
                        }
                        mw_util = utils.utility_version(params.deploy_util_version, 'deploy_util_version')
                        
                        if(!params.branch) {
                            nexus = "-e nexus_download='true' -e ap_p2m_integration_version=${params.ap_p2m_integration_version}"
                        }
                        else {
                            nexus = "-e nexus_download='false' -e ap_p2m_integration_version=\$ap_p2m_integration_version"
                        }
                    }
                    sh "mkdir -p ansible-${ansible_branch}"
                    dir ("ansible-${ansible_branch}") {
                        git branch: "${ansible_branch}", credentialsId: 'zapp.jenkins.build', url: "http://bitbucket.vocalink.co.uk/scm/zapp/ansible.git"
                        sh "ansible-galaxy install --force -r requirements.yml -p roles/"
                        sh """
                            ap_p2m_integration_version=\$(cat $WORKSPACE/${component}/pom.xml |grep -m1 '<version>.*</version>' |awk -F'[><]' '{print \$3}')
                            ansible-playbook -i inv/hosts.yml ap-p2m-integration.yml -e env=${pipelineParams.env} -c local -e workspace=$WORKSPACE \
                            -e nexus_user=${git_credentials_usr} -e nexus_pass=${git_credentials_psw} -e download_artifacts=false \
                            $mw_util $nexus
                        """
                    }
                }
            }

            stage('Deploy ') {
                steps {
                    dir ('ap-middleware-deployment-utility') {
                        sh "./runAPUtil.sh -Dconfig_properties_url=$WORKSPACE/properties/config-ap-deploy.properties"
                    }
                }
            }

        }
    }        
}
