#!/usr/bin/env groovy

pipeline {
    agent any

    parameters {
        // === Homologados con SAST ===
        string(name: 'FOD_URL', defaultValue: 'https://api.ams.fortify.com',
            description: 'FoD API URL')
        string(name: 'FOD_RELEASE_ID', defaultValue: '1388854',
            description: 'FoD Release ID')
        string(name: 'SCAN_TIMEOUT_MINUTES', defaultValue: '120',
            description: 'Timeout in minutes for single wait-for attempt')
        string(name: 'WAIT_RETRIES', defaultValue: '2',
            description: 'Number of additional wait-for retry attempts (after the first one)')
        string(name: 'WAIT_RETRY_DELAY_MINUTES', defaultValue: '2',
            description: 'Minutes to wait between retries')

        // === Específicos de SCA ===
        string(name: 'BUILD_TOOL', defaultValue: 'maven',
            description: 'Build tool for dependency resolution (maven/gradle/npm/yarn)')
        string(name: 'SCA_OUTPUT', defaultValue: 'sca-results.json',
            description: 'File where OSS scan results will be exported')
    }

    environment {
        APP_NAME       = "IWA-JAVA-2024"
        APP_VERSION    = "Github-2025"
        FOD_CLIENT_ID     = credentials('iwa-fod-client-id')
        FOD_CLIENT_SECRET = credentials('iwa-fod-client-secret')
        GIT_URL        = "https://github.com/aisraelr/fortify-IWA-2024.git"
        GIT_REPO_NAME  = "fortify-IWA-2024"
    }

    stages {
        stage('Initialize') {
            steps {
                script {
                    env.GIT_COMMIT = bat(script: "git rev-parse HEAD", returnStdout: true).trim()
                    env.GIT_BRANCH = bat(script: "git rev-parse --abbrev-ref HEAD", returnStdout: true).trim()
                    env.SCAN_TIMEOUT_MINUTES_INT = params.SCAN_TIMEOUT_MINUTES.toInteger()
                }
            }
        }

        stage('Prepare fcli') {
            steps {
                script {
                    def LOCAL_FCLI = "C:\\tools\\fcli\\fcli.exe"
                    def FCLI_HOME = "${env.WORKSPACE}\\fcli"
                    def FCLI_EXE = "${FCLI_HOME}\\fcli.exe"

                    if (fileExists(LOCAL_FCLI)) {
                        echo "[INFO] Usando fcli preinstalado en ${LOCAL_FCLI}"
                        env.FCLI_PATH = LOCAL_FCLI
                    } else {
                        echo "[INFO] Validando fcli en workspace..."
                        bat """
                            @echo off
                            if not exist "${FCLI_HOME}" mkdir "${FCLI_HOME}"
                            if not exist "${FCLI_EXE}" (
                                echo [INFO] Descargando fcli...
                                curl -L https://github.com/fortify/fcli/releases/download/v3.8.1/fcli-windows.zip -o "${FCLI_HOME}\\fcli-windows.zip"
                                tar -xf "${FCLI_HOME}\\fcli-windows.zip" -C "${FCLI_HOME}" fcli.exe
                            ) else (
                                echo [INFO] fcli.exe ya existe en ${FCLI_HOME}
                            )
                        """
                        env.FCLI_PATH = FCLI_EXE
                    }
                    echo "[INFO] fcli en uso: ${env.FCLI_PATH}"
                }
            }
        }

        stage('Login to FoD') {
            steps {
                script {
                    bat """
                        "${env.FCLI_PATH}" fod session login ^
                            --url ${params.FOD_URL} ^
                            --client-id ${env.FOD_CLIENT_ID} ^
                            --client-secret ${env.FOD_CLIENT_SECRET} ^
                            --session sca-session
                    """
                }
            }
        }

        stage('FoD SCA (OSS) Scan') {
            steps {
                script {
                    bat """
                        "${env.FCLI_PATH}" fod oss scan start ^
                            --release ${params.FOD_RELEASE_ID} ^
                            --build-tool ${params.BUILD_TOOL} ^
                            --output-file ${params.SCA_OUTPUT} ^
                            --session sca-session ^
                            --wait-for-results ${params.SCAN_TIMEOUT_MINUTES}
                    """
                }
            }
        }

        stage('Export OSS Results') {
            steps {
                script {
                    bat """
                        echo Exportando resultados OSS a ${params.SCA_OUTPUT}
                        type ${params.SCA_OUTPUT} || echo No se generó archivo de resultados.
                    """
                }
            }
        }
    }

    post {
        always {
            script {
                echo "=== RESUMEN EJECUCIÓN SCA ==="
                echo "   Status: ${currentBuild.currentResult}"
                echo "   SCA Results File: ${params.SCA_OUTPUT}"
            }
        }
    }
}
