@Library('zapp-utilities') _

import com.zapp.utilities
utils = new utilities()

pipeline {

	agent {
		label 'zapp-dev-env'
	}

	parameters {
		string(name: 'branch', defaultValue:'develop', description: 'Branch to release from')
		string(name: 'release_version', defaultValue: '', description: 'Release Version')
		string(name: 'development_version', defaultValue: '', description: 'Next Development Version')
		string(name: 'settings_file', defaultValue: 'pwba-settings', description: 'The settings.xml file to be used')
		string(name: 'component', defaultValue: null, description: '')
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
		 * Required User Credentials
		 */
		git_credentials = credentials('zapp.jenkins.build')
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
		stage('Checkout the source branch for the release') {
			steps {
				deleteDir()
				git branch: "${params.branch}", credentialsId: 'zapp.jenkins.build', url: "http://bitbucket.vocalink.co.uk/scm/zapp/${params.component}.git"
				script{
					currentBuild.description = "Release ${params.component} ${params.release_version}"
				}
			}
		}

		stage('Update module versions to release version') {
			steps {
				script {
					utils.mvn("-B versions:set -DnewVersion=${params.release_version} -P all,dev-env-v3 -e", settings_id)
				}
			}
		}

		stage ('Build the released modules') {
			steps {
				script {
					utils.mvn("-B clean install -DskipTests -P all,dev-env-v3 -e", settings_id)
				}
			}
		}

		stage('Commit, Tag and Push the released module versions') {
			steps {
				withCredentials([usernamePassword(credentialsId: 'zapp.jenkins.build', passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
					sh "git remote add bitbucket http://${GIT_USERNAME}:${GIT_PASSWORD}@bitbucket.vocalink.co.uk/scm/zapp/${params.component}.git"
					sh "git commit -am \'${params.component} Released with version ${params.release_version}\'"
					sh "git tag -a ${params.release_version} -m \'Released with version ${params.release_version}\'"
					sh "git push bitbucket ${params.branch}"
					sh "git push bitbucket ${params.release_version}"
				}
			}
		}

		stage('Deploy the released modules to Nexus') {
			steps {
				script {
					utils.mvn("-B deploy -DskipTests -P all,dev-env-v3", settings_id)
				}
			}
		}

		stage('Update modules to the new snapshot version') {
			steps {
				script {
					utils.mvn("-B versions:set -DnewVersion=${params.development_version} -P all,dev-env-v3 -e", settings_id)
				}
			}
		}

		stage ('Build the new snapshot modules') {
			steps {
				script {
					utils.mvn("-B clean install -DskipTests -P all,dev-env-v3 -e", settings_id)
				}
			}
		}

		stage('Commit and Push the new snapshot module versions') {
			steps {
				withCredentials([usernamePassword(credentialsId: 'zapp.jenkins.build', passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
					sh "git commit -am \'${params.component} Next development version updated to ${params.development_version}\'"
					sh "git push bitbucket ${params.branch}"
				}
			}
		}

		stage('Deploy new snapshot modules to Nexus') {
			steps {
				script {
					utils.mvn("-B deploy -DskipTests -P all,dev-env-v3", settings_id)
				}
			}
		}
	}
}
