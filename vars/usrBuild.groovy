def call(service, serviceFolder) {
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
                        // sh 'curl -sfL https://github.com/aquasecurity/trivy/releases/download/v0.24.0/trivy_0.24.0_Linux-64bit.tar.gz | tar xz -C /usr/local/bin'
                    }
                }
            }
            stage ('Build') {
                steps {
                    script {
                        // install all required python modules for each service
                        sh "venv/bin/pip install -r ${serviceFolder}/requirements.txt"

                        // build all the docker images
                        sh "docker build -t ${service} ${serviceFolder}/"
                    }
                }
            }
            stage ('Lint') {
                steps {
                    script {
                        sh '. venv/bin/activate' // Activates venv for pylint to recognize
                        sh "pylint --fail-under 5 ${serviceFolder}/*.py"
                    }
                }
            }
            stage ('Security Scan') {
                environment {
                    TRIVY_USR_PSW_TOKEN = credentials('trivy')
                    TRIVY_CACHE_DIR = "${env.WORKSPACE}/.trivy_cache"
                }
                steps {
                    script {
                        sh """
                            export TRIVY_GITHUB_TOKEN=${TRIVY_USR_PSW_TOKEN_PSW}
                            trivy --cache-dir ${TRIVY_CACHE_DIR} image ${service}
                        """
                    }
                }
            }
            stage ('Package') {
                steps {
                    withCredentials([string(credentialsId: 'usr_dockerhub', variable: 'TOKEN')]) {
                        sh """
                            docker login -u "kodymills395" -p "$TOKEN" docker.io
                            docker image tag ${service} kodymills395/${service}:latest
                            docker push kodymills395/${service}:latest
                        """
                    }
                }
            }
            stage ('Deploy') {
                environment {
                    AWS_HOST = 'ec2-3-91-213-74.compute-1.amazonaws.com'
                    AWS_CREDENTIALS=credentials('acit3855_ssh_access')
                }
                when {
                    expression { params.DEPLOY }
                }
                steps {
                    sshagent(credentials: ['acit3855_ssh_access']) {
                        sh """
                            ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null ${AWS_CREDENTIALS_USR}@${AWS_HOST} '
                                docker pull kodymills395/${service}:latest
                                docker compose -f acit3855-lab8/Deployment/docker-compose.yml up -d
                            '
                        """                 
                    }
                }
            }
        }
    }
}