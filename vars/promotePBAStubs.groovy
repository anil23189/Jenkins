import com.zapp.utilities

def call(body) {
    utils = new utilities()
    def pipelineParams= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    pipeline {

        parameters{
            string(name: 'pba_stubs_version', defaultValue: '', description: 'stubs version')
            string(name: 'branch', defaultValue: null, description: 'set to deploy from branch')
            string(name: 'commit', defaultValue: null, description: '')
            string(name: 'multiversion', defaultValue: '', description: 'multiversion suffix, leave blank if not required')

            
        }

        environment{
            git_credentials = credentials('zapp.jenkins.build')
            ansible_branch = "wlst_deploy"
            property_branch = "property_deploy"
            settings_id = "pwba-settings"
            component = "ap-pba-stubs"

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

            stage('Build'){
                when {
                    expression { return params.branch }
                }
                steps {
                    sh "mkdir ${component}"
                    dir ("${component}"){
                        git branch: "${params.branch}", credentialsId: 'zapp.jenkins.build', url: "http://bitbucket.vocalink.co.uk/scm/zapp/${component}.git"
                        script {
                            if (params.commit) {
                                sh "git checkout ${params.commit}"
                                currentBuild.description = "Deploy Stubs ${params.commit}"
                            }
                            else {
                                currentBuild.description = "Deploy Stubs ${params.branch}"
                            }
                            utils.mvn("clean install -Pall,dev-env-v3,jenkins -DskipTests", settings_id)
                        }
                    }
                }

            }

            stage('Deploy Properties') {
                steps {
                    sh "mkdir -p ansible-${property_branch}"
                    dir ("ansible-${property_branch}"){
                        git branch: "${property_branch}", credentialsId: 'zapp.jenkins.build', url: 'http://bitbucket.vocalink.co.uk/scm/zapp/ansible.git'
                        withCredentials([sshUserPrivateKey(credentialsId: 'zapp-apdev', keyFileVariable: 'apkeyfile', passphraseVariable: '', usernameVariable: 'ssh_user')]) {
                            sh """
                            ansible-playbook -i inv/hosts.yml properties.yml -e env=${pipelineParams.env} -e key1=$apkeyfile -e ssh_user=$ssh_user \
                            -e multiversion=${params.multiversion} -e workspace=$WORKSPACE \
                            --tags "pba_stubs"
                            """
                        }
                    }
                    
                }
            }

            stage('Prepare WLST scripts') {
                steps {
                    script {
                        if(!params.branch) {
                            nexus = "-e nexus_download='true' -e version=${params.pba_stubs_version}"
                        }
                        else {
                            nexus = "-e nexus_download='false' -e version=\$stubs_version"
                        }
                    }
                    sh "mkdir -p ansible-${ansible_branch}"
                    dir ("ansible-${ansible_branch}") {
                        dir ("ansible-${ansible_branch}"){
                            git branch: "${ansible_branch}", credentialsId: 'zapp.jenkins.build', url: 'http://bitbucket.vocalink.co.uk/scm/zapp/ansible.git'
                            withCredentials([sshUserPrivateKey(credentialsId: 'zapp-apdev', keyFileVariable: 'apkeyfile', passphraseVariable: '', usernameVariable: 'ssh_user')]) {
                                sh """
                                
                                stubs_version=\$(cat $WORKSPACE/${component}/pom.xml |grep -m1 '<version>.*</version>' |awk -F'[><]' '{print \$3}')
                                ansible-playbook -i inv/hosts.yml deploy.yml -c local -e env=${pipelineParams.env} -e workspace=$WORKSPACE\
                                -e component=${component} $nexus -e deploy_list="pba-bank-stub,pba-distributor-stub" \
                                -e component_target_path="$WORKSPACE/${component}/assembly" -e version=\$stubs_version -e multiversion=${params.multiversion} \
                                -e nexus_user=${git_credentials_usr} -e nexus_pass=${git_credentials_psw}
                                """     
                            }
                        }
                    }
                }
            }
            stage("WLST Deploy"){
                steps {
                    dir ("scripts") {
                    sh """
                        chmod +x $WORKSPACE/scripts/wlst_deploy.sh
                        ./wlst_deploy.sh
                    """
                    }
                }
            }
        }
    }        
}
