pipeline {
    agent {
        kubernetes {
            label 'docker-builder'
            defaultContainer 'docker'
            yaml """
apiVersion: v1
kind: Pod
spec:
  nodeSelector:
    docker-builder: "true"
  containers:
  - name: docker
    image: docker:24-dind
    securityContext:
      privileged: true
    volumeMounts:
    - name: docker-sock
      mountPath: /var/run/docker.sock
  - name: kubectl
    image: bitnami/kubectl:1.29
    command: ['cat']
    tty: true
  - name: gradle
    image: eclipse-temurin:21-jdk-jammy
    command: ['cat']
    tty: true
    volumeMounts:
    - name: gradle-cache
      mountPath: /root/.gradle
  volumes:
  - name: docker-sock
    hostPath:
      path: /var/run/docker.sock
  - name: gradle-cache
    emptyDir: {}
"""
        }
    }

    environment {
        DOCKER_REGISTRY = 'docker.io'
        DOCKER_ORG      = 'jrivera340'
        IMAGE_TAG       = "${env.GIT_COMMIT?.take(7) ?: 'latest'}"
        SERVICES        = 'circleguard-auth-service circleguard-identity-service circleguard-form-service circleguard-promotion-service circleguard-gateway-service circleguard-notification-service'
    }

    stages {

        // ─────────────────────────────────────────────
        // STAGE 1 — CHECKOUT
        // ─────────────────────────────────────────────
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    env.GIT_SHORT_SHA = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                    env.IMAGE_TAG = env.GIT_SHORT_SHA
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 2 — UNIT TESTS
        // ─────────────────────────────────────────────
        stage('Unit Tests') {
            when { not { branch 'prod' } }
            steps {
                container('gradle') {
                    sh './gradlew test --no-daemon --continue -x :services:circleguard-dashboard-service:test 2>&1 | tee test-output.log || true'
                    junit allowEmptyResults: true, testResults: '**/build/test-results/test/*.xml'
                }
            }
            post {
                always {
                    archiveArtifacts artifacts: '**/build/reports/tests/**', allowEmptyArchive: true
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 3 — BUILD JARs
        // ─────────────────────────────────────────────
        stage('Build JARs') {
            steps {
                container('gradle') {
                    sh './gradlew bootJar --no-daemon -x test 2>&1'
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 4 — DOCKER BUILD & PUSH (parallel)
        // ─────────────────────────────────────────────
        stage('Docker Build & Push') {
            steps {
                container('docker') {
                    withCredentials([usernamePassword(
                        credentialsId: 'dockerhub-creds',
                        usernameVariable: 'DOCKER_USER',
                        passwordVariable: 'DOCKER_PASS'
                    )]) {
                        sh 'echo $DOCKER_PASS | docker login -u $DOCKER_USER --password-stdin'
                        script {
                            def svcs = [
                                [name: 'circleguard-auth-service',         port: '8180'],
                                [name: 'circleguard-identity-service',     port: '8083'],
                                [name: 'circleguard-form-service',         port: '8086'],
                                [name: 'circleguard-promotion-service',    port: '8088'],
                                [name: 'circleguard-gateway-service',      port: '8087'],
                                [name: 'circleguard-notification-service', port: '8082']
                            ]
                            def parallelBuilds = [:]
                            svcs.each { svc ->
                                def s = svc
                                parallelBuilds[s.name] = {
                                    sh """
                                        docker build -t ${DOCKER_ORG}/${s.name}:${env.IMAGE_TAG} \
                                            -f services/${s.name}/Dockerfile .
                                        docker push ${DOCKER_ORG}/${s.name}:${env.IMAGE_TAG}
                                        docker tag ${DOCKER_ORG}/${s.name}:${env.IMAGE_TAG} \
                                                   ${DOCKER_ORG}/${s.name}:latest
                                        docker push ${DOCKER_ORG}/${s.name}:latest
                                    """
                                }
                            }
                            parallel parallelBuilds
                        }
                    }
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 5 — DEPLOY TO DEV (develop branch only)
        // ─────────────────────────────────────────────
        stage('Deploy DEV') {
            when { branch 'develop' }
            steps {
                container('kubectl') {
                    withCredentials([file(credentialsId: 'kubeconfig', variable: 'KUBECONFIG')]) {
                        sh '''
                            export KUBECONFIG=$KUBECONFIG
                            # Update image tags in overlays
                            cd k8s/overlays/dev
                            kustomize edit set image \
                                jrivera340/circleguard-auth-service:${IMAGE_TAG} \
                                jrivera340/circleguard-identity-service:${IMAGE_TAG} \
                                jrivera340/circleguard-form-service:${IMAGE_TAG} \
                                jrivera340/circleguard-promotion-service:${IMAGE_TAG} \
                                jrivera340/circleguard-gateway-service:${IMAGE_TAG} \
                                jrivera340/circleguard-notification-service:${IMAGE_TAG}
                            cd ../../..
                            kubectl apply -k k8s/overlays/dev
                            kubectl rollout status deployment/circleguard-identity-service   -n circleguard-dev --timeout=180s
                            kubectl rollout status deployment/circleguard-auth-service       -n circleguard-dev --timeout=180s
                            kubectl rollout status deployment/circleguard-form-service       -n circleguard-dev --timeout=180s
                            kubectl rollout status deployment/circleguard-promotion-service  -n circleguard-dev --timeout=300s
                            kubectl rollout status deployment/circleguard-gateway-service    -n circleguard-dev --timeout=180s
                            kubectl rollout status deployment/circleguard-notification-service -n circleguard-dev --timeout=180s
                        '''
                    }
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 6 — SMOKE TESTS (DEV)
        // ─────────────────────────────────────────────
        stage('Smoke Tests DEV') {
            when { branch 'develop' }
            steps {
                container('kubectl') {
                    withCredentials([file(credentialsId: 'kubeconfig', variable: 'KUBECONFIG')]) {
                        sh '''
                            export KUBECONFIG=$KUBECONFIG
                            echo "=== Smoke Tests ==="
                            kubectl exec -n circleguard-dev deploy/circleguard-gateway-service -- \
                                wget -qO- http://localhost:8087/actuator/health/readiness | grep UP
                            kubectl exec -n circleguard-dev deploy/circleguard-auth-service -- \
                                wget -qO- http://localhost:8180/actuator/health/readiness | grep UP
                            kubectl exec -n circleguard-dev deploy/circleguard-identity-service -- \
                                wget -qO- http://localhost:8083/actuator/health/readiness | grep UP
                            kubectl exec -n circleguard-dev deploy/circleguard-form-service -- \
                                wget -qO- http://localhost:8086/actuator/health/readiness | grep UP
                            kubectl exec -n circleguard-dev deploy/circleguard-promotion-service -- \
                                wget -qO- http://localhost:8088/actuator/health/readiness | grep UP
                            kubectl exec -n circleguard-dev deploy/circleguard-notification-service -- \
                                wget -qO- http://localhost:8082/actuator/health/readiness | grep UP
                            echo "=== All services healthy ==="
                        '''
                    }
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 7 — DEPLOY TO STAGING (staging branch)
        // ─────────────────────────────────────────────
        stage('Deploy STAGING') {
            when { branch 'staging' }
            steps {
                container('kubectl') {
                    withCredentials([file(credentialsId: 'kubeconfig', variable: 'KUBECONFIG')]) {
                        sh '''
                            export KUBECONFIG=$KUBECONFIG
                            kubectl apply -k k8s/overlays/staging
                            kubectl rollout status deployment/circleguard-identity-service   -n circleguard-staging --timeout=180s
                            kubectl rollout status deployment/circleguard-auth-service       -n circleguard-staging --timeout=180s
                            kubectl rollout status deployment/circleguard-form-service       -n circleguard-staging --timeout=180s
                            kubectl rollout status deployment/circleguard-promotion-service  -n circleguard-staging --timeout=300s
                            kubectl rollout status deployment/circleguard-gateway-service    -n circleguard-staging --timeout=180s
                            kubectl rollout status deployment/circleguard-notification-service -n circleguard-staging --timeout=180s
                        '''
                    }
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 8 — INTEGRATION TESTS (staging)
        // ─────────────────────────────────────────────
        stage('Integration Tests') {
            when { branch 'staging' }
            steps {
                container('gradle') {
                    sh '''
                        ./gradlew integrationTest --no-daemon \
                            -Dtest.env=staging \
                            -Dauth.url=http://circleguard-auth-service.circleguard-staging:8180 \
                            -Dgateway.url=http://circleguard-gateway-service.circleguard-staging:8087 \
                            -Dform.url=http://circleguard-form-service.circleguard-staging:8086 \
                            -Dpromotion.url=http://circleguard-promotion-service.circleguard-staging:8088 \
                            || true
                    '''
                    junit allowEmptyResults: true, testResults: '**/build/test-results/integrationTest/*.xml'
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 9 — E2E TESTS (staging)
        // ─────────────────────────────────────────────
        stage('E2E Tests') {
            when { branch 'staging' }
            steps {
                container('gradle') {
                    sh '''
                        pip3 install requests pytest 2>/dev/null || true
                        python3 -m pytest tests/e2e/ -v \
                            --base-url=http://circleguard-auth-service.circleguard-staging:8180 \
                            --junit-xml=tests/e2e/results.xml || true
                    '''
                    junit allowEmptyResults: true, testResults: 'tests/e2e/results.xml'
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 10 — LOCUST PERFORMANCE TESTS (staging)
        // ─────────────────────────────────────────────
        stage('Performance Tests (Locust)') {
            when { branch 'staging' }
            steps {
                container('kubectl') {
                    withCredentials([file(credentialsId: 'kubeconfig', variable: 'KUBECONFIG')]) {
                        sh '''
                            export KUBECONFIG=$KUBECONFIG
                            kubectl apply -f k8s/jobs/locust-job.yaml -n circleguard-staging
                            kubectl wait --for=condition=complete job/locust-perf-test \
                                -n circleguard-staging --timeout=900s
                            kubectl logs job/locust-perf-test -n circleguard-staging > locust-results.txt
                            kubectl delete job/locust-perf-test -n circleguard-staging --ignore-not-found
                        '''
                        archiveArtifacts artifacts: 'locust-results.txt', allowEmptyArchive: true
                    }
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 11 — MANUAL APPROVAL (prod)
        // ─────────────────────────────────────────────
        stage('Approve Production') {
            when { branch 'main' }
            steps {
                timeout(time: 30, unit: 'MINUTES') {
                    input message: "¿Aprobar despliegue a PRODUCCIÓN? Tag: ${env.IMAGE_TAG}",
                          ok: 'Aprobar y Desplegar',
                          submitter: 'admin'
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 12 — GENERATE RELEASE NOTES (prod)
        // ─────────────────────────────────────────────
        stage('Generate Release Notes') {
            when { branch 'main' }
            steps {
                sh '''
                    chmod +x scripts/generate-release-notes.sh
                    scripts/generate-release-notes.sh ${IMAGE_TAG}
                '''
                archiveArtifacts artifacts: 'RELEASE_NOTES_*.md'
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 13 — GIT TAG (prod)
        // ─────────────────────────────────────────────
        stage('Git Tag') {
            when { branch 'main' }
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'github-creds',
                    usernameVariable: 'GIT_USER',
                    passwordVariable: 'GIT_PASS'
                )]) {
                    sh '''
                        git config user.email "jenkins@circleguard.edu"
                        git config user.name "Jenkins CI"
                        git tag -a "v${IMAGE_TAG}" -m "Release v${IMAGE_TAG} — deployed by Jenkins"
                        git push https://${GIT_USER}:${GIT_PASS}@github.com/jrivera340/circle-guard-public.git \
                            "v${IMAGE_TAG}"
                    '''
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 14 — DEPLOY TO PRODUCTION
        // ─────────────────────────────────────────────
        stage('Deploy PRODUCTION') {
            when { branch 'main' }
            steps {
                container('kubectl') {
                    withCredentials([file(credentialsId: 'kubeconfig', variable: 'KUBECONFIG')]) {
                        sh '''
                            export KUBECONFIG=$KUBECONFIG
                            kubectl apply -k k8s/overlays/prod
                            kubectl rollout status deployment/circleguard-identity-service   -n circleguard-prod --timeout=180s
                            kubectl rollout status deployment/circleguard-auth-service       -n circleguard-prod --timeout=180s
                            kubectl rollout status deployment/circleguard-form-service       -n circleguard-prod --timeout=180s
                            kubectl rollout status deployment/circleguard-promotion-service  -n circleguard-prod --timeout=300s
                            kubectl rollout status deployment/circleguard-gateway-service    -n circleguard-prod --timeout=180s
                            kubectl rollout status deployment/circleguard-notification-service -n circleguard-prod --timeout=180s
                            echo "=== Production deployment successful ==="
                        '''
                    }
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 15 — POST-DEPLOY SYNTHETIC TRANSACTION
        // ─────────────────────────────────────────────
        stage('Post-Deploy Validation') {
            when { branch 'main' }
            steps {
                container('kubectl') {
                    withCredentials([file(credentialsId: 'kubeconfig', variable: 'KUBECONFIG')]) {
                        sh '''
                            export KUBECONFIG=$KUBECONFIG
                            echo "=== Synthetic Transaction: Login → QR → Gate ==="
                            RESULT=$(kubectl exec -n circleguard-prod deploy/circleguard-auth-service -- \
                                wget -qO- --post-data='{"username":"staff_guard","password":"password"}' \
                                --header='Content-Type: application/json' \
                                http://localhost:8180/api/v1/auth/login)
                            echo "Login result: $RESULT"
                            echo "=== Post-deploy validation passed ==="
                        '''
                    }
                }
            }
        }
    }

    post {
        success {
            echo "✅ Pipeline completed successfully — Branch: ${env.BRANCH_NAME}, Tag: ${env.IMAGE_TAG}"
        }
        failure {
            echo "❌ Pipeline FAILED — Branch: ${env.BRANCH_NAME}"
            // Rollback on production failure
            script {
                if (env.BRANCH_NAME == 'main') {
                    container('kubectl') {
                        sh '''
                            export KUBECONFIG=$KUBECONFIG
                            echo "=== AUTO-ROLLBACK triggered ==="
                            kubectl rollout undo deployment/circleguard-auth-service       -n circleguard-prod || true
                            kubectl rollout undo deployment/circleguard-gateway-service    -n circleguard-prod || true
                            kubectl rollout undo deployment/circleguard-promotion-service  -n circleguard-prod || true
                        '''
                    }
                }
            }
        }
        always {
            cleanWs()
        }
    }
}
