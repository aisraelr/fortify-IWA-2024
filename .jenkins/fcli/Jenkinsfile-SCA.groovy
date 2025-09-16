#!/usr/bin/env groovy

// Variable global para persistir el Scan ID
def GLOBAL_SCAN_ID = ""

pipeline {
    agent any

    parameters {
        string(name: 'FOD_URL', defaultValue: 'https://api.ams.fortify.com',
            description: 'FoD API URL')
        string(name: 'FOD_RELEASE_ID', defaultValue: '1388854',
            description: 'FoD Release ID')
        string(name: 'SCAN_TIMEOUT_MINUTES', defaultValue: '120',
            description: 'Timeout en minutos para un intento de wait-for')
        string(name: 'WAIT_RETRIES', defaultValue: '2',
            description: 'Número de reintentos adicionales de wait-for')
        string(name: 'WAIT_RETRY_DELAY_MINUTES', defaultValue: '2',
            description: 'Minutos a esperar entre reintentos')
    }

    environment {
        APP_NAME       = "IWA-JAVA-2024"
        APP_VERSION    = "Github-2025"
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

        stage('Build') {
            steps {
                bat "mvn clean package -DskipTests"
                archiveArtifacts artifacts: "target/*.jar", fingerprint: true
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
                                curl -L https://github.com/fortify/fcli/releases/latest/download/fcli-windows.zip -o "${FCLI_HOME}\\fcli-windows.zip"
                                powershell -Command "Expand-Archive -Path '${FCLI_HOME}\\fcli-windows.zip' -DestinationPath '${FCLI_HOME}' -Force"
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

        stage('FoD SCA (OSS) Scan') {
            steps {
                script {
                    def FCLI_HOME = "${env.WORKSPACE}\\fcli"

                    withCredentials([
                        string(credentialsId: 'iwa-fod-client-id', variable: 'FOD_CLIENT_ID'),
                        string(credentialsId: 'iwa-fod-client-secret', variable: 'FOD_CLIENT_SECRET')
                    ]) {
                        bat """
                            @echo off
                            echo [INFO] Logging into FoD for OSS Scan...
                            "${FCLI_HOME}\\fcli.exe" fod session login --client-id "%FOD_CLIENT_ID%" --client-secret "%FOD_CLIENT_SECRET%" --url "${params.FOD_URL}" --fod-session jenkins

                            echo [INFO] Starting OSS Scan...
                            "${FCLI_HOME}\\fcli.exe" fod oss-scan start --rel "${params.FOD_RELEASE_ID}" --file "oss-scan.zip" --fod-session jenkins --output json > oss-scan-output.json

                            echo [INFO] Logging out...
                            "${FCLI_HOME}\\fcli.exe" fod session logout --fod-session jenkins
                        """

                        // Leer resultados
                        if (fileExists('oss-scan-output.json')) {
                            def ossResults = readFile('oss-scan-output.json')
                            echo "OSS Scan Output:\n${ossResults}"

                            // Extraer Scan ID del JSON si es necesario
                            def json = readJSON text: ossResults
                            GLOBAL_SCAN_ID = json?.scanId ?: null
                            if (!GLOBAL_SCAN_ID) {
                                echo "⚠️ No se pudo extraer el Scan ID del output"
                            } else {
                                echo "✅ Scan ID capturado: ${GLOBAL_SCAN_ID}"
                                currentBuild.displayName = "#${BUILD_NUMBER} - SCA ${GLOBAL_SCAN_ID}"
                            }
                        } else {
                            error "❌ No se generó oss-scan-output.json"
                        }
                    }
                }
            }
        }

    }

    post {
        always {
            echo "=== RESUMEN EJECUCIÓN SCA ==="
            echo "Status: ${currentBuild.currentResult}"
            echo "SCA Results File: sca_output.txt"
        }
    }
}

// Función helper para extraer Scan ID
def extractScanId(output) {
    def matcher = output =~ /Scan ID:\s*(\d+)/
    return matcher ? matcher[0][1] : null
}
