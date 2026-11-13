pipeline {
    agent any

    environment {
        JAVA_HOME     = '/usr/lib/jvm/java-1.8.0-amazon-corretto'

        // Staging (fli-stg) deployment settings
        STG_HOST      = 'fli-stg'
        STG_USER      = 'ubuntu'
        STG_SSH_KEY   = '/var/lib/jenkins/.ssh/fli-stg.pem'   // path to the fli-stg key on the Jenkins server
        STG_APP_DIR   = '/home/ubuntu/fineract'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    // BRANCH_NAME is set in multibranch jobs; GIT_BRANCH (e.g. origin/fli-qa) otherwise
                    def branch = env.BRANCH_NAME ?: env.GIT_BRANCH ?: ''
                    env.DEPLOY_BRANCH = branch.replaceFirst(/^origin\//, '')
                    echo "Branch: ${env.DEPLOY_BRANCH}"
                    if (!(env.DEPLOY_BRANCH in ['fli-qa', 'fli-staging'])) {
                        error "Branch '${env.DEPLOY_BRANCH}' is not a deployable branch (expected fli-qa or fli-staging)"
                    }
                }
            }
        }

        // ---------------- QA (fli-qa): deploy locally on this server ----------------

        stage('Stop (QA)') {
            when { expression { env.DEPLOY_BRANCH == 'fli-qa' } }
            steps {
                sh 'sudo systemctl stop fineract || true'
            }
        }

        // ---------------- Build (shared by QA and staging) ----------------

        stage('Build') {
            steps {
                dir('fineract-provider') {
                    sh './gradlew clean jar copyDeps'
                }
            }
        }

        stage('Deploy (QA)') {
            when { expression { env.DEPLOY_BRANCH == 'fli-qa' } }
            steps {
                sh 'sudo systemctl restart fineract'
            }
        }

        // ---------------- Staging (fli-staging): deploy to fli-stg over SSH ----------------

        stage('Deploy (Staging)') {
            when { expression { env.DEPLOY_BRANCH == 'fli-staging' } }
            steps {
                dir('fineract-provider/build/libs') {
                    sh '''
                        set -e
                        JAR=$(ls *.jar | grep -v -- '-all' | head -n 1)
                        if [ -z "$JAR" ]; then
                            echo "No jar found in build/libs"; exit 1
                        fi
                        echo "Deploying $JAR to ${STG_HOST}"

                        SSH_OPTS="-i ${STG_SSH_KEY} -o StrictHostKeyChecking=accept-new"

                        # 1. Upload jar to /tmp on fli-stg
                        scp $SSH_OPTS "$JAR" ${STG_USER}@${STG_HOST}:/tmp/

                        # 2. Copy into app dir and 3. restart fineract
                        ssh $SSH_OPTS ${STG_USER}@${STG_HOST} "
                            set -e
                            cp /tmp/$JAR ${STG_APP_DIR}/
                            sudo systemctl restart fineract
                            sleep 5
                            sudo systemctl is-active fineract
                        "
                    '''
                }
            }
        }
    }

    post {
        failure {
            echo "Build/deploy failed for ${env.DEPLOY_BRANCH}!"
        }
        success {
            echo "Build/deploy succeeded for ${env.DEPLOY_BRANCH}!"
        }
    }
}
