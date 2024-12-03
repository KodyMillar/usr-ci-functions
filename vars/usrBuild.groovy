def call() {
    pipeline {
        agent { label 'python_agent' }
        parameters {
            booleanParam(defaultValue: false, description: 'Deploy to Production', name: 'DEPLOY')
        }
        stages {
            stage ('Setup') {
                steps {
                    script {
                        echo "Running ${env.BUILD_ID} in workspace ${env.WORKSPACE}"
                        boolean venvExists = fileExists('venv')
                        if (venvExists) {
                            sh 'rm -r venv || true'
                        }
                        sh 'python3 -m venv venv'
                        sh 'ls -l venv'

                        // install trivy for image vulnerability scanning
                        sh 'curl -sfL https://github.com/aquasecurity/trivy/releases/download/v0.24.0/trivy_0.24.0_Linux-64bit.tar.gz | tar xz -C /usr/local/bin'
                    }
                }
            }
            stage ('Build') {
                steps {
                    script {
                        // install all required python modules for each service
                        sh 'venv/bin/pip install -r Receiver/requirements.txt'
                        sh 'venv/bin/pip install -r Storage/requirements.txt'
                        sh 'venv/bin/pip install -r Processor/requirements.txt'
                        sh 'venv/bin/pip install -r Analyzer/requirements.txt'

                        // build all the docker images
                        sh 'build -t receiver Receiver/'
                        sh 'built -t storage Storage/'
                        sh 'build -t processing Processor/'
                        sh 'build -t analyzer Analyzer/'
                    }
                }
            }
            stage ('Lint') {
                steps {
                    script {
                        sh 'pylint --fail-under 5 Receiver/*.py'
                        sh 'pylint --fail-under 5 Storage/*.py'
                        sh 'pylint --fail-under 5 Processor/*.py'
                        sh 'pylint --fail-under 5 Analyzer/*.py'
                    }
                }
            }
            stage ('Security Scan') {
                steps {
                    script {
                        sh '''
                            trivy image receiver
                            trivy image storage
                            trivy image processing
                            trivy image analyzer
                        '''
                    }
                }
            }
            stage ('Package') {
                steps {
                    withCredentials([string(credentialsId: 'usr_dockerhub', variable: 'TOKEN')]) {
                        sh '''
                            docker login -u "kodymills395" -p "$TOKEN" docker.io
                            docker image tag receiver kodymills395/receiver:latest
                            docker image tag storage kodymills395/storage:latest
                            docker image tag processing kodymills395/processing:latest
                            docker image tag analyzer kodymills395/analyzer:latest 
                            docker push kodymills395/receiver:latest
                            docker push kodymills395/storage:latest
                            docker push kodymills395/processing:latest
                            docker push kodymills395/analyzer:latest
                        '''
                    }
                }
            }
            stage ('Deploy') {
                when {
                    expression { params.DEPLOY }
                }
                steps {
                    sshagent(credentials: ['acit3855_ssh_access']) {
                        sh '''
                            docker pull kodymills395/receiver:latest
                            docker pull kodymills395/storage:latest
                            docker pull kodymills395/processing:latest
                            docker pull kodymills395/analyzer:latest
                            docker compose -f acit3855-lab8/Deployment/docker-compose.yml up -d
                        '''                    
                    }
                }
            }
        }
    }
}