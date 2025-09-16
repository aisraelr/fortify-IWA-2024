#!/usr/bin/env groovy

// Variable global para persistir el Scan ID entre stages y en post
def GLOBAL_SCAN_ID = ""

pipeline {
    agent any

    parameters {
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
    }

    environment {
        FCLI_HOME       = "C:\\tools\\fcli"
        FOD_CLIENT_ID     = credentials('iwa-fod-client-id')
        FOD_CLIENT_SECRET = credentials('iwa-fod-client-secret')
    }

    stages {
        stage('Build') {
            steps {
                bat "mvn clean package -DskipTests"
                archiveArtifacts artifacts: "target/*.jar", fingerprint: true
            }
        }

        stage('Prepare OSS Zip') {
            steps {
                script {
                    echo "[INFO] Generando oss-scan.zip..."
                    bat """
                        @echo off
                        if exist oss-scan.zip del /f /q oss-scan.zip
                        powershell -Command "Compress-Archive -Path target\\* -DestinationPath oss-scan.zip -Force"
                    """
                    if (!fileExists('oss-scan.zip')) {
                        error "❌ No se pudo generar oss-scan.zip"
                    }
                }
            }
        }

        stage('FoD SCA (OSS) Scan') {
            steps {
                script {
                    withCredentials([
                        string(credentialsId: 'iwa-fod-client-id', variable: 'FOD_CLIENT_ID'),
                        string(credentialsId: 'iwa-fod-client-secret', variable: 'FOD_CLIENT_SECRET')
                    ]) {
                        bat """
                            @echo off
                            echo [INFO] Logging into FoD for OSS Scan...
                            "${env.FCLI_HOME}\\fcli.exe" fod session login --client-id "%FOD_CLIENT_ID%" --client-secret "%FOD_CLIENT_SECRET%" --url "${params.FOD_URL}" --fod-session jenkins

                            echo [INFO] Starting OSS Scan...
                            "${env.FCLI_HOME}\\fcli.exe" fod oss-scan start --rel "${params.FOD_RELEASE_ID}" --file "oss-scan.zip" --fod-session jenkins --output json > oss-scan-output.json

                            echo [INFO] Logging out...
                            "${env.FCLI_HOME}\\fcli.exe" fod session logout --fod-session jenkins
                        """

                        // Verificar y mostrar el JSON
                        if (fileExists('oss-scan-output.json')) {
                            def ossResults = readFile('oss-scan-output.json')
                            echo "OSS Scan Output:\n${ossResults}"

                            // Extraer Scan ID del JSON (si aplica)
                            def jsonObj = readJSON file: 'oss-scan-output.json'
                            if (jsonObj.scanId) {
                                GLOBAL_SCAN_ID = jsonObj.scanId.toString()
                                echo "✅ Scan ID capturado: ${GLOBAL_SCAN_ID}"
                                currentBuild.displayName = "#${BUILD_NUMBER} - OSS Scan ${GLOBAL_SCAN_ID}"
                            } else {
                                echo "⚠️ No se encontró Scan ID en oss-scan-output.json"
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
            echo "SCA Results File: oss-scan-output.json"
        }
    }
}
