import com.zapp.utilities

def call(body) {
    utils = new utilities()
    def pipelineParams= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    pipeline {


        environment{
            settings_id = "${pipelineParams.settings_id}"
            git_credentials = credentials('zapp.jenkins.build')
            component = "ap-pba-stubs"
            ansible_branch = "wlst_deploy"
            property_branch = "property_deploy"
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
                            stubs_version = utils.version(component)
                        }
                    }
                    
                }
            }

            stage('Deploy Properties') {
                when {
                    branch "PR*"
                    changeRequest target: "${pipelineParams.target}"
                }
                steps {
                    sh "mkdir -p ansible-${property_branch}"
                    dir ("ansible-${property_branch}") {
                        git branch: "${property_branch}", credentialsId: 'zapp.jenkins.build', url: "http://bitbucket.vocalink.co.uk/scm/zapp/ansible.git"
                        withCredentials([sshUserPrivateKey(credentialsId: 'zapp-apdev', keyFileVariable: 'apkeyfile', passphraseVariable: '', usernameVariable: 'ssh_user')]) {
                            sh """
                            ansible-playbook -i inv/hosts.yml properties.yml -e env=cb -e key1=$apkeyfile -e ssh_user=$ssh_user -e workspace=$WORKSPACE \
                            --tags "pba_stubs"
                            """
                        }
                    }
                }
            }

            stage('Lock CB') {
                options {
                    lock(resource: 'stubs-cb')
                }
                stages {
                    stage("Prepare WLST scripts"){
                        steps {
                            sh "mkdir -p ansible-${ansible_branch}"
                            dir ("ansible-${ansible_branch}") {
                                git branch: "${ansible_branch}", credentialsId: 'zapp.jenkins.build', url: "http://bitbucket.vocalink.co.uk/scm/zapp/ansible.git"
                                withCredentials([sshUserPrivateKey(credentialsId: 'zapp-apdev', keyFileVariable: 'apkeyfile', passphraseVariable: '', usernameVariable: 'ssh_user')]) {
                                    sh """
                                    stubs_version=\$(cat $WORKSPACE/${component}/pom.xml |grep -m1 '<version>.*</version>' |awk -F'[><]' '{print \$3}')
                                    ansible-playbook -i inv/hosts.yml deploy.yml -c local -e env=cb -e version=\$stubs_version -e workspace=$WORKSPACE\
                                    -e nexus_download="false" -e deploy_list="pba-bank-stub,pba-distributor-stub" \
                                    -e component_target_path="$WORKSPACE/${component}/assembly"
                                    """
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

                    stage('Integration Tests') {
                        when {
                           // branch 'PR*'
                           branch 'NEVER'
                            changeRequest target: "${pipelineParams.target}"
                        }
                        steps {
                            dir ("${component}"){
                                script {
                                    utils.mvn(pipelineParams.test_goal, settings_id)
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
                  //  branch 'PR*'
                  branch 'NEVER'
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
