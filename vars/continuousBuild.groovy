import com.zapp.*

def call(body) {
    utils = new utilities()
    def pipelineParams= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

	pipeline {
		
		agent {
			label "${pipelineParams.agent}"
		}
		
		parameters{
			string(name: 'deploy_util_version', defaultValue: '5.5.11', description: 'leave blank to take version from ansible vars')
			string(name: 'db_deploy_util_version', defaultValue: '', description: 'leave blank to take version from ansible vars')
			string(name: 'ansible_branch', defaultValue: 'master', description: 'leave blank to take version from ansible vars')
		}
		
		environment {
			/**
			 * Tools version
			 */
			java_v = 'jdk1.7_17'
			maven_v = 'Maven 3.0.4'
			settings_id = 'pwba-settings'	
			MAVEN_OPTS="-Xmx4g -Xms4g -XX:+UseCompressedOops -XX:MaxPermSize=512m -XX:+UseConcMarkSweepGC -XX:-UseGCOverheadLimit"

			/**
			 * Repo URLs
			 */
			ansible_repo_url = "http://bitbucket.vocalink.co.uk/scm/zapp/ansible.git"
		
			/**
			 * Application paths
			 */
			component="${pipelineParams.component}"
						
			/**
			 * Deploy utils
			 */			
			mw_deployment_util = "middleware-deployment-utility"
			db_deployment_util = "database-deployment-utility"		
			wlst_deployment_util = "wlst-deploy-utility"
			
			/**
			 * Environment paths
			 */
			ORACLE_HOME = "/fs01/app/oracle/product/Client11g"
			PATH = "$PATH:$ORACLE_HOME/bin"
			db_test_dir = "${WORKSPACE}/properties/test"
			config_path = "$WORKSPACE/properties/"
			
			/**
			 * Required User Credentials
			 */
			nexus_token = credentials('zapp.nexus.build.token')
			sonar_credentials = credentials('jenkins.sonarqube.token')
			git_credentials = credentials('zapp.jenkins.build')
		}
		
		options {
			buildDiscarder(logRotator(numToKeepStr: '4'))
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
						sh "mkdir ${component}"
						sh "mkdir ansible"
					}
				}
			}
			
			stage('Checkout') {
				steps {
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
						db_util = utils.utility_version(params.db_deploy_util_version, 'ap_database_deployment_utility_version')
						component_version = utils.version(component,settings_id)
					}
					dir ("$WORKSPACE/ansible") {	
						checkout([$class: 'GitSCM', 
							branches: [[name: params.ansible_branch]],
							doGenerateSubmoduleConfigurations: false, 
							extensions: [[$class: 'SparseCheckoutPaths', sparseCheckoutPaths: [
								[$class: 'SparseCheckoutPath', path: "/${component}/"], 
								[$class: 'SparseCheckoutPath', path: "/${mw_deployment_util}/roles/${mw_deployment_util}"], 
								[$class: 'SparseCheckoutPath', path: "/${db_deployment_util}/roles/${db_deployment_util}"]]							
							]],
							submoduleCfg: [],
							userRemoteConfigs: [[credentialsId: 'zapp.jenkins.build', url: ansible_repo_url]]
						])					
							
						sh "mv $WORKSPACE/ansible/${mw_deployment_util}/roles/${mw_deployment_util} $WORKSPACE/ansible/${db_deployment_util}/roles/${db_deployment_util} $WORKSPACE/ansible/${component}/roles/"
						sh "rm -rf $WORKSPACE/ansible/${mw_deployment_util} $WORKSPACE/ansible/${db_deployment_util}"
						sh "cp $WORKSPACE/${component}/properties/*.properties.j2 $WORKSPACE/ansible/${component}/roles/${component}/templates/ 2>/dev/null || :"
											
						dir ("$WORKSPACE/ansible/${component}") {
							withCredentials([sshUserPrivateKey(credentialsId: 'zapp-apdev', keyFileVariable: 'apkeyfile', passphraseVariable: '', usernameVariable: 'ssh_user')]) {
								sh """
									ansible-playbook -i inv/hosts.yml ${component}.yml -e env=${pipelineParams.env} -e workspace=$WORKSPACE \
									-e nexus_user=${git_credentials_usr} -e nexus_pass=${git_credentials_psw} -e key1=$apkeyfile -e ssh_user=$ssh_user \
									-e component_version=${component_version} -e nexus_download="false" -e download_artifacts=false \
									$mw_util
									"""
							}
						}
					}
				}
			}
			
			stage('Lock CB') {
				options {
					lock(resource: pipelineParams.lock_mdm_db)
				}
				stages {
					stage('Deploy DB to CB11') {
						when {
							expression {
								return fileExists("$WORKSPACE/properties/app-user-config.properties")			
							}
						}
						steps {
							sh "mkdir -p /tmp/${pipelineParams.env}"
							dir ('ap-database-deployment-utility') {
								sh "chmod +x ap-db-deploy-bamboo.sh"
								sh """
								echo 'Y'| ./ap-db-deploy-bamboo.sh -DuserConfigFile=$WORKSPACE/properties/app-user-config.properties -DconfigLocation=$WORKSPACE/properties/
								"""
							}
						}
					}

					stage('Unit Tests') {
						when {
							expression { pipelineParams.test_goal != null }
						}
						steps {
							dir ("${component}"){
								script {
									utils.mvn(pipelineParams.test_goal, settings_id)
								}
							}
						}
					}
					
					stage('Deploy App (Middleware Utility)')	{
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
								return fileExists("$WORKSPACE/ansible/${component}/roles/${component}/templates/config-deploy-wlst.properties.j2")			
							}
						}
						stages() {					
							stage("Prepare WLST scripts"){
								steps {
									script {
										component_version = utils.version(component,settings_id)
									}
									dir ("$WORKSPACE/ansible") {
										checkout([$class: 'GitSCM', 
											branches: [[name: params.ansible_branch]],
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
												ansible-playbook -i inv/hosts.yml wlst-deploy-utility.yml -e env=${pipelineParams.env} -e component_version=${component_version} -e workspace=$WORKSPACE\
												-e nexus_download="false" -e component_name=${component} -e component_target_path="$WORKSPACE/${component}/assembly"
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
					
					stage('Integration Tests') {
						when {
							expression { pipelineParams.it_test_goal != null }
						}
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
				when {
					 expression { pipelineParams.sonar_goal != null }
				}
				tools {
					jdk 'jdk1.8_192'
				}
				steps {
					dir ("${component}") {
						script {
							withSonarQubeEnv("ZAPP_SonarQube") {
							utils.mvn(pipelineParams.sonar_goal, settings_id)
							}
						}
					}
				}
			}
			
			stage('Parallel stage for Incremental DB deploy') {
				when {
					branch 'NEVER'
				}
				parallel {                        

					stage('Deploy to CB31'){
						when {
							expression {
								return fileExists("${config_path}/cb/app-user-config-cb31.properties")			
							}
						}
						options {
							lock(resource: 'CB31_APMDM')
						}
						steps {
							sh "cp -r ap-database-deployment-utility ap-database-deployment-utility-cb31"
							dir("ap-database-deployment-utility-cb31") {
								sh "./ap-db-deploy-bamboo.sh -DuserConfigFile=${config_path}/cb/app-user-config-cb31.properties -DconfigLocation=${config_path}/cb"
							}
						}
					}      
					
					stage('Deploy to CB32'){
						when {
							expression {
								return fileExists("${config_path}/cb/app-user-config-cb32.properties")			
							}
						}
						options {
							lock(resource: 'CB32_APMDM')
						}
						steps {
							sh "cp -r ap-database-deployment-utility ap-database-deployment-utility-cb32"
							dir("ap-database-deployment-utility-cb32") {
								sh "./ap-db-deploy-bamboo.sh -DuserConfigFile=${config_path}/cb/app-user-config-cb32.properties -DconfigLocation=${config_path}/cb"
							}
						}
					}
					
					stage('Deploy to CB22') {
						when {
							expression {
								return fileExists("${config_path}/cb/app-user-config-cb22.properties")			
							}
						}
						options {
							lock(resource: 'CB22_APMDM')
						}
						steps {
							sh "cp -r ap-database-deployment-utility ap-database-deployment-utility-cb22"
							dir("ap-database-deployment-utility-cb22") {
								sh "./ap-db-deploy-bamboo.sh -DuserConfigFile=${config_path}/cb/app-user-config-cb22.properties -DconfigLocation=${config_path}/cb"
							}
						}
					}
					
				}

			}
			
			stage('Deploy to Nexus') {
				when {
					branch "${pipelineParams.branch}"
				}
				steps {
					dir ("${component}"){
						script {
							utils.mvn("clean deploy -Pdev-test-env-v3 -DskipTests -Dweblogic.home=/home/devops/fmw/wlserver_10.3 -Dosb.home=/home/devops/fmw/osb", settings_id)
						}
					}
				}
			}
		}
	}        
}