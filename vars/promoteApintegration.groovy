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
            string(name: 'ap_integration_version', defaultValue: '', description: 'ap_integration_version')
            string(name: 'properties_version', defaultValue: 'develop', description: 'osb properties file version')
            string(name: 'branch', defaultValue: null, description: 'set to deploy from branch')
            string(name: 'commit', defaultValue: null, description: '')
            
        }

        environment{
            git_credentials = credentials('zapp.jenkins.build')
            component = "ap-integration"
            settings_id = "pwba-settings"
            ansible_branch = "ap-integration"
            MAVEN_OPTS="-Xmx4g -Xms4g -XX:+UseCompressedOops -XX:MaxPermSize=512m -XX:+UseConcMarkSweepGC -XX:-UseGCOverheadLimit"
        }

        agent {
            label 'zapp-dev-env2'
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
                        git branch: "${params.branch}", credentialsId: 'zapp.jenkins.build', url: "http://bitbucket.vocalink.co.uk/scm/zapp/ap-integration.git"
                        script {
                            if (params.commit) {
                                sh "git checkout ${params.commit}"
                                currentBuild.description = "Deploy Integration ${params.commit}"
                            }
                            else {
                                currentBuild.description = "Deploy Integration ${params.branch}"
                            }
                            utils.mvn("clean install -e -Pdev-test-env-v4-pbba,jenkins -DskipTests -Dweblogic.home=/fs01/app/oracle/product/fmw/wlserver_10.3 -Dosb.home=/fs01/app/oracle/product/fmw/osb -Dsun.lang.ClassLoader.allowArraySyntax=true", settings_id)
                        }
                    }
                }
            }
            stage('Prepare Ansible') {
                steps {
                    script {
                        if(!params.branch){
                            currentBuild.description = "Deploy MIntegration ${params.ap_integration_version}"
                        }
                        mw_util = utils.utility_version(params.deploy_util_version, 'deploy_util_version')
                        
                        if(!params.branch) {
                            nexus = "-e nexus_download='true' -e ap_integration_version=${params.ap_integration_version}"
                        }
                        else {
                            nexus = "-e nexus_download='false'"
                            ap_integration_version = utils.version(component)
                        }
                    }
                    sh "mkdir -p ansible-${ansible_branch}"
                    dir ("ansible-${ansible_branch}") {
                        git branch: "${ansible_branch}", credentialsId: 'zapp.jenkins.build', url: "http://bitbucket.vocalink.co.uk/scm/zapp/ansible.git"
                        sh "ansible-galaxy install --force -r requirements.yml -p roles/"
                        sh """
                            ansible-playbook -i inv/hosts.yml ap-integration.yml -e env=${pipelineParams.env} -c local -e workspace=$WORKSPACE \
                            -e nexus_user=${git_credentials_usr} -e nexus_pass=${git_credentials_psw} \
                            $mw_util $nexus -e ap_integration_version=${ap_integration_version} -e properties_version=${params.properties_version}
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
