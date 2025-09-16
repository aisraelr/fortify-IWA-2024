#!/usr/bin/env groovy

def GLOBAL_SCAN_ID = ""

pipeline {
    agent any

    parameters {
        string(name: 'FOD_URL', defaultValue: 'https://api.ams.fortify.com',
               description: 'FoD API URL')
        string(name: 'FOD_RELEASE_ID', defaultValue: '1388854',
               description: 'FoD Release ID')
        string(name: 'SCAN_TIMEOUT_MINUTES', defaultValue: '120',
               description: 'Timeout in minutes for wait-for')
        string(name: 'WAIT_RETRIES', defaultValue: '2',
               description: 'Additional wait-for retry attempts')
        string(name: 'WAIT_RETRY_DELAY_MINUTES', defaultValue: '2',
               description: 'Minutes to wait between retries')
    }

    environment {
        FCLI_HOME = "C:\\tools\\fcli"
        FOD_CLIENT_ID = credentials('iwa-fod-client-id')
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
                    bat """
                        @echo off
                        if exist oss-scan.zip del /f /q oss-scan.zip
                        powershell -Command "Compress-Archive -Path target\\* -DestinationPath oss-scan.zip -Force"
                    """
                    if (!fileExists('oss-scan.zip')) error "❌ No se pudo generar oss-scan.zip"
                }
            }
        }

        stage('FoD OSS Scan') {
            steps {
                script {
                    withCredentials([
                        string(credentialsId: 'iwa-fod-client-id', variable: 'FOD_CLIENT_ID'),
                        string(credentialsId: 'iwa-fod-client-secret', variable: 'FOD_CLIENT_SECRET')
                    ]) {
                        // Iniciar OSS scan
                        bat """
                            @echo off
                            echo [INFO] Logging into FoD for OSS Scan...
                            "${env.FCLI_HOME}\\fcli.exe" fod session login --client-id "%FOD_CLIENT_ID%" --client-secret "%FOD_CLIENT_SECRET%" --url "${params.FOD_URL}" --fod-session jenkins

                            echo [INFO] Starting OSS Scan...
                            "${env.FCLI_HOME}\\fcli.exe" fod oss-scan start --rel "${params.FOD_RELEASE_ID}" --file "oss-scan.zip" --fod-session jenkins --output json > oss-scan-start.json

                            echo [INFO] Logging out...
                            "${env.FCLI_HOME}\\fcli.exe" fod session logout --fod-session jenkins
                        """

                        // Leer Scan ID
                        def startJson = readJSON file: 'oss-scan-start.json'
                        GLOBAL_SCAN_ID = startJson.scanId.toString()
                        echo "✅ OSS Scan started: ID ${GLOBAL_SCAN_ID}"
                    }
                }
            }
        }

        stage('Wait for OSS Scan Completion') {
            steps {
                script {
                    if (!GLOBAL_SCAN_ID) error "❌ GLOBAL_SCAN_ID no definido."

                    int retries = params.WAIT_RETRIES.toInteger()
                    int delayMin = params.WAIT_RETRY_DELAY_MINUTES.toInteger()
                    int maxAttempts = 1 + retries
                    boolean success = false

                    withCredentials([
                        string(credentialsId: 'iwa-fod-client-id', variable: 'FOD_CLIENT_ID'),
                        string(credentialsId: 'iwa-fod-client-secret', variable: 'FOD_CLIENT_SECRET')
                    ]) {
                        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                            echo "🔁 Wait-for attempt ${attempt} of ${maxAttempts} (timeout ${params.SCAN_TIMEOUT_MINUTES}m)..."
                            def rc = bat(script: """
                                @echo off
                                echo [INFO] Logging into FoD...
                                "${env.FCLI_HOME}\\fcli.exe" fod session login --client-id "%FOD_CLIENT_ID%" --client-secret "%FOD_CLIENT_SECRET%" --url "${params.FOD_URL}" --fod-session jenkins

                                echo [INFO] Waiting for OSS Scan ${GLOBAL_SCAN_ID}...
                                "${env.FCLI_HOME}\\fcli.exe" fod oss-scan wait-for ${GLOBAL_SCAN_ID} --fod-session jenkins --timeout ${params.SCAN_TIMEOUT_MINUTES}m --output json > last-oss-scan.json

                                echo [INFO] Logging out...
                                "${env.FCLI_HOME}\\fcli.exe" fod session logout --fod-session jenkins
                            """, returnStatus: true)

                            if (rc == 0) {
                                echo "✅ wait-for succeeded on attempt ${attempt}"
                                success = true
                                break
                            } else {
                                echo "⚠️ wait-for attempt ${attempt} failed (rc=${rc})"
                                if (attempt < maxAttempts) {
                                    echo "⏳ Esperando ${delayMin} minutos antes de reintentar..."
                                    sleep time: delayMin, unit: 'MINUTES'
                                }
                            }
                        }

                        if (!success) error "❌ OSS Scan no finalizó después de ${maxAttempts} intentos."
                    }
                }
            }
        }

        stage('Validate OSS Scan Results') {
            steps {
                script {
                    if (!fileExists('last-oss-scan.json')) error "❌ No se encontró last-oss-scan.json con resultados."

                    def rawText = readFile('last-oss-scan.json')
                    echo "📝 OSS Scan JSON Output:"
                    echo rawText

                    def scanResults = readJSON file: 'last-oss-scan.json'
                    def status = scanResults.analysisStatusType ?: 'Unknown'
                    def criticalCount = scanResults.issueCountCritical ?: 0
                    def highCount = scanResults.issueCountHigh ?: 0

                    echo "📊 OSS SCAN RESULTS for ID: ${GLOBAL_SCAN_ID}"
                    echo "   Status: ${status}"
                    echo "   Critical Issues: ${criticalCount}"
                    echo "   High Issues: ${highCount}"

                    if (criticalCount > 10) error "❌ BUILD FAILED: Critical issues (${criticalCount}) > threshold"
                    if (highCount > 10) error "❌ BUILD FAILED: High issues (${highCount}) > threshold"

                    echo "✅ OSS Scan PASSED"
                }
            }
        }
    }

    post {
        always {
            echo "=== RESUMEN EJECUCIÓN OSS SCA ==="
            echo "Status: ${currentBuild.currentResult}"
            echo "SCA Results File: last-oss-scan.json"
        }
    }
}
