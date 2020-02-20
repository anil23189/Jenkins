@Library('zapp-utilities') _

import com.zapp.utilities
utils = new utilities()

pipeline {

	environment {
		/**
		 * Tools version
		 */
		java_v = 'jdk1.7_17'
		maven_v = 'Maven 3.0.4'
		settings_id = 'pwba-settings'

		/**
		 * Environment paths
		 */				
		ORACLE_HOME = "/fs01/app/oracle/product/Client11g"
		PATH = "$PATH:$ORACLE_HOME/bin"
		db_test_dir = "${WORKSPACE}/properties/test"
		MAVEN_OPTS="-Xmx4g -Xms4g -XX:+UseCompressedOops -XX:MaxPermSize=512m -XX:+UseConcMarkSweepGC -XX:-UseGCOverheadLimit"

		/**
		 * Repo URLs
		 */
		component_repo_url = "http://bitbucket.vocalink.co.uk/scm/zapp/${params.component}.git"
		ansible_repo_url = "http://bitbucket.vocalink.co.uk/scm/zapp/ansible.git"

		/**
		 * Deploy utils
		 */			
		mw_deployment_util = "middleware-deployment-utility"
		db_deployment_util = "database-deployment-utility"
		wlst_deployment_util = "wlst-deploy-utility"

		/**
		 * Required User Credentials
		 */
		nexus_token = credentials('zapp.nexus.build.token')		
		git_credentials = credentials('zapp.jenkins.build')
	}
	
	agent {
		label "${params.agent}"
	}

	options {
		buildDiscarder(logRotator(numToKeepStr: '10'))
		skipDefaultCheckout(true)
		disableConcurrentBuilds()
	}

	tools {
		maven "${maven_v}"
		jdk "${java_v}"
	}

	stages {

		stage('Env Set Up') {
			steps {
				script {
					deleteDir()
					sh "mkdir ${params.component}"
					sh "mkdir ansible"
				}
			}
		}

		stage('Build') {
			when {
				expression { return params.branch }
			}
			steps {
				dir ("${params.component}"){
					git branch: "${params.branch}", credentialsId: 'zapp.jenkins.build', url: component_repo_url
					script {
						if (params.commit) {
							sh "git checkout ${params.commit}"
							currentBuild.description = "Deploy ${params.component} ${params.commit}"
						}
						else {
							currentBuild.description = "Deploy ${params.component} ${params.branch}"
						}
						utils.mvn("clean install -Pall,dev-env-v3,jenkins,dev-test-env-v3-digital,dev-test-env-v4-pbba,update-dev-timeout -DskipTests -Dsun.lang.ClassLoader.allowArraySyntax=true -Dbuild-test-stub -Dweblogic.home=/home/devops/fmw/wlserver_10.3 -Dosb.home=/home/devops/fmw/osb", settings_id)
					}
				}
			}
		}

		stage('Prepare Ansible') {
			steps {
				script {			
					component_version = params.component_version ?: ''
					ansible_branch = params.ansible_branch ?: 'master'
					deploy_path = params.deploy_path ?: '/fs01/app/adapter-deployment/backoffice-sprint-test/zapp-client-adaptor'
					mw_util = utils.utility_version(params.deploy_util_version ?: '', 'deploy_util_version')
					db_util = utils.utility_version(params.db_deploy_util_version?: '', 'ap_database_deployment_utility_version')
					if(!params.branch) {
						nexus = "-e nexus_download='true' -e component_version=${component_version}"
						currentBuild.description = "Deploy ${params.component} ${component_version}"
					}
					else {
						component_version = utils.version(params.component,settings_id)
						nexus = "-e nexus_download='false' -e component_version=${component_version}"
					}
				}
				dir ("$WORKSPACE/ansible") {
					checkout([$class: 'GitSCM', 
						branches: [[name: ansible_branch]],
						doGenerateSubmoduleConfigurations: false, 
						extensions: [
							[$class: 'SparseCheckoutPaths', sparseCheckoutPaths: [
							[$class: 'SparseCheckoutPath', path: "/${params.component}/"], 
							[$class: 'SparseCheckoutPath', path: "/${mw_deployment_util}/roles/${mw_deployment_util}"], 
							[$class: 'SparseCheckoutPath', path: "/${db_deployment_util}/roles/${db_deployment_util}"]]						
						]],
						submoduleCfg: [],
						userRemoteConfigs: [[credentialsId: 'zapp.jenkins.build', url: ansible_repo_url]]
					])
					
					sh "mv $WORKSPACE/ansible/${mw_deployment_util}/roles/${mw_deployment_util} $WORKSPACE/ansible/${db_deployment_util}/roles/${db_deployment_util} $WORKSPACE/ansible/${params.component}/roles/"
					sh "rm -rf $WORKSPACE/ansible/${mw_deployment_util} $WORKSPACE/ansible/${db_deployment_util}"
					sh "cp $WORKSPACE/${params.component}/properties/*.properties.j2 $WORKSPACE/ansible/${params.component}/roles/${params.component}/templates/ 2>/dev/null || :"					
					
					dir ("$WORKSPACE/ansible/${params.component}") {
						withCredentials([sshUserPrivateKey(credentialsId: 'zapp-apdev', keyFileVariable: 'apkeyfile', passphraseVariable: '', usernameVariable: 'ssh_user')]) {
							sh """                    
									ansible-playbook -i inv/hosts.yml ${params.component}.yml -e env=${params.target_environment} -e workspace=$WORKSPACE \
									-e nexus_user=${git_credentials_usr} -e nexus_pass=${git_credentials_psw} -e download_artifacts=false \
									-e key1=$apkeyfile -e ssh_user=$ssh_user -e deploy_path=${deploy_path} \
									-e properties_version=${params.properties_version} $db_util $mw_util $nexus
								"""
						}
					}
				}
			}
		}

		stage('Deploy DB ') {
			when {
				expression {
					return fileExists("$WORKSPACE/properties/app-user-config.properties")			
				}
			}
			steps {
				sh "mkdir -p /tmp/${params.target_environment}"
				dir ('ap-database-deployment-utility') {
					sh "chmod +x ap-db-deploy-bamboo.sh"
					sh """
                        ./ap-db-deploy-bamboo.sh -DuserConfigFile=$WORKSPACE/properties/app-user-config.properties -DconfigLocation=$WORKSPACE/properties/
                        """
				}
			}
		}

		stage('Deploy apps (Middleware Utility)') {
			when {
				expression {
					return fileExists("$WORKSPACE/properties/config-deploy.properties")			
				}
			}
			steps {
				dir ('ap-middleware-deployment-utility') {
					sh """
                        ./runAPUtil.sh -Dconfig_properties_url=$WORKSPACE/properties/config-deploy.properties
                        """
				}
			}
		}
		
		stage ("Deploy App (WLST)") {
			when {
				expression {
					return fileExists("$WORKSPACE/ansible/${params.component}/roles/${params.component}/templates/config-deploy-wlst.properties.j2")			
				}
			}
			stages() {					
				stage("Prepare WLST scripts"){
					steps {
						script {
							component_version = utils.version(params.component,settings_id)
							ansible_branch = params.ansible_branch ?: 'master'
						}
						dir ("$WORKSPACE/ansible") {
							checkout([$class: 'GitSCM', 
								branches: [[name: ansible_branch]],
								doGenerateSubmoduleConfigurations: false, 
								extensions: [
									[$class: 'SparseCheckoutPaths', sparseCheckoutPaths: [[$class: 'SparseCheckoutPath', path: "/${wlst_deployment_util}/"]]							
								]],
								submoduleCfg: [],
								userRemoteConfigs: [[credentialsId: 'zapp.jenkins.build', url: ansible_repo_url]]
							])
						
							dir ("$WORKSPACE/ansible/${wlst_deployment_util}") {
								withCredentials([sshUserPrivateKey(credentialsId: 'zapp-apdev', keyFileVariable: 'apkeyfile', passphraseVariable: '', usernameVariable: 'ssh_user')]) {
									sh """
									ansible-playbook -i inv/hosts.yml wlst-deploy-utility.yml -e env=${params.target_environment} -e component_version=${component_version} -e workspace=$WORKSPACE\
									-e nexus_download="false" -e component_name=${params.component} -e component_target_path="$WORKSPACE/${params.component}/assembly"
									"""
								}
							}
						}
					}
				}
				
				stage("Deploy App (WLST)") {
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
}