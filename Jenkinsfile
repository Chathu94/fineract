pipeline {
    agent any

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Stop') {
            steps {
                sh 'systemctl stop fineract || true'
            }
        }

        stage('Build') {
            steps {
                dir('fineract-provider') {
                    sh 'JAVA_HOME=/usr/lib/jvm/java-1.8.0-amazon-corretto ./gradlew clean jar copyDeps'
                }
            }
        }

        stage('Deploy') {
            steps {
                sh 'systemctl restart fineract'
            }
        }
    }

    post {
        failure {
            echo 'Build failed!'
        }
        success {
            echo 'Build succeeded!'
        }
    }
}