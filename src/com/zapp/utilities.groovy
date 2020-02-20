package com.zapp

    def mvn(goal, settings_id) {
        configFileProvider(
	    [configFile(fileId: "${settings_id}", variable: 'settings_file')]) {
                sh "mvn -s ${settings_file} ${goal}"
	    }
	}
    def utility_version(util_version, ansible_param) {
        if (!util_version?.trim()) {
            deploy_util = ""
        }
        else {
            deploy_util = "-e $ansible_param=$util_version"
        }
        return deploy_util
    }

    def cleanup(component, settings_id){
        dir(component){
            script{
                    mvn("clean -q", settings_id)
            }
        }
        if (fileExists('ap-database-deployment-utility')){
            dir ('ap-database-deployment-utility') {
                deleteDir()
            }
        }
        if (fileExists('ap-middleware-deployment-utility')){
            dir ('ap-middleware-deployment-utility') {
                deleteDir()
            }
        }

    }
	
	def version(component,settings_id){
		dir("${WORKSPACE}/${component}")
		{
			configFileProvider(
				[configFile(fileId: "${settings_id}", variable: 'settings_file')]) {
						return sh (script : "mvn -s ${settings_file} help:evaluate -Dexpression=project.version -q -DforceStdout", returnStdout: true)
				}
		}
	}

    def version(component){
        return(readFile("${WORKSPACE}/${component}/pom.xml") =~ '<version>(.+)</version>')[0][1]
    }
	
	
    def db_deploy(config_path, env, build_type) {
        db_deploy_command = "./ap-db-deploy-bamboo.sh -DuserConfigFile=${config_path}/app-user-config-${env}.properties -DconfigLocation=${config_path}"
        sh "cp -r ap-database-deployment-utility ap-database-deployment-utility-${env}"
        dir("ap-database-deployment-utility-${env}"){
            if (build_type != "INCREMENTAL") {
                sh "echo 'Y'|${db_deploy_command}"
            }
            else {
                sh "${db_deploy_command}"
            }
        }              
    }
