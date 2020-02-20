import com.zapp.utilities

def call(body) {
    utils = new utilities()
    def pipelineParams= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    pipeline {

        parameters{
            string(name: 'deploy_util_version', defaultValue: '4.2.2', description: 'deploymen utility version')

            
        }
        
        environment{
            settings_id = "${pipelineParams.settings_id}"
            git_credentials = credentials('zapp.jenkins.build')
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
            stage('Checkout') {
                steps {
                    deleteDir()
                    checkout scm
                    script{
                        currentBuild.description = ""
                    }
                }
            }


            //stage('Build') {
               // when {
                //    anyOf { branch "${pipelineParams.branch}"; branch 'PR*'}
               // }

               // steps {
                  //script {
                 //       utils.mvn(pipelineParams.build_goal, settings_id)
                //    }
                    
               // }
         //   }


            stage('Deploy to CB') {
                //when {
                   // branch 'PR*'
                   // changeRequest target: "${pipelineParams.target}"
               // }
                steps {
                    sh "mkdir cb_deployment"
                    dir ('cb_deployment') {
                        git branch: "feature/ap-pos-nfc-integration-test", credentialsId: 'zapp.jenkins.build', url: "http://bitbucket.vocalink.co.uk/scm/zapp/ansible.git"
                        sh """
                        ap_pos_nfc_integration_version=\$(cat ../pom.xml |grep -m1 '<version>.*</version>' |awk -F'[><]' '{print \$3}')
                        ansible-playbook -i inv/hosts.yml ap-pos-nfc-integration.yml -e env=${pipelineParams.env} -c local -e workspace=$WORKSPACE -e apps=pos_nfc_osb \
                        -e nexus_user=${git_credentials_usr} -e nexus_pass=${git_credentials_psw} -e deploy_util_version=${params.deploy_util_version} \
                        -e ap_pos_nfc_integration_version=\$ap_pos_nfc_integration_version -e download_artifacts=false 
                        """
                    }

                    //sh "mv services/OSB_Bundle/target/OSB_Bundle-*.gz ap-middleware-deployment-utility/download/"
                   // sh "mv services/OSB_Deployment/target/OSB_Deployment-*.jar ap-middleware-deployment-utility/download/"
                    dir ('ap-middleware-deployment-utility') {
                      sh "./runAPUtil.sh -Dconfig_properties_url=$WORKSPACE/properties/config-ap-pos-nfc-integration.properties"
                    }
                }
            }

           // stage('Integration Tests') {
             //   when {
                 //  branch 'PR*'
                  //  changeRequest target: "${pipelineParams.target}"
             //   }
               // steps {
                  //  script {
                 //       utils.mvn(pipelineParams.test_goal, settings_id)
                    
                   // }
                //}

           // }

           // stage('Deploy to Nexus') {
               // when {
                   // branch "${pipelineParams.branch}"
                  //anyOf { branch "${pipelineParams.branch}"; branch 'PR*'}
               // }
                //steps {
                    //script {
                      //  utils.mvn("clean deploy -DskipTests -Dweblogic.home=/home/devops/fmw/wlserver_10.3 -Dosb.home=/home/devops/fmw/osb", settings_id)
                   // }
              //  }
            
          //  }

           // stage('Publish test Results') {
              //  when {
                   // branch 'PR*'
              //  }
               // steps {
                  //  junit '**/target/surefire-reports/*.xml'
               // }
            //}
        }
    }        
}
