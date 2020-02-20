import com.zapp.utilities

def call(body) {
  	utils = new utilities()

    def pipelineParams= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()
    pipeline {

        parameters {
            string(name: 'branch', defaultValue:'develop', description: 'Branch to release from')
            string(name: 'release_version', defaultValue: '', description: 'Release Version')
            string(name: 'development_version', defaultValue: '', description: 'Next Development Version')
            string(name: 'settings_file', defaultValue: 'pwba-settings', description: 'The settings.xml file to be used')
        }

        environment {
            settings_id = "${params.settings_file}"
            git_credentials = credentials('zapp.jenkins.build')
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
            stage('Checkout the source branch for the release') {
                steps {
                    deleteDir()
                    git branch: "${params.branch}", credentialsId: 'zapp.jenkins.build', url: "http://bitbucket.vocalink.co.uk/scm/zapp/${pipelineParams.repo_name}.git"
                    script{
                        currentBuild.description = "Release ${pipelineParams.repo_name} ${params.release_version}"
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
                        sh "git remote add bitbucket http://${GIT_USERNAME}:${GIT_PASSWORD}@bitbucket.vocalink.co.uk/scm/zapp/${pipelineParams.repo_name}.git"
                        sh "git commit -am \'${pipelineParams.comment_prefix} Released with version ${params.release_version}\'"
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
                        sh "git commit -am \'${pipelineParams.comment_prefix} Next development version updated to ${params.development_version}\'"
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
}
