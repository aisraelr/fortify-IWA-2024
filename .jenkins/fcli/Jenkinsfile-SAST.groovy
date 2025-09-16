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
        string(name: 'CRITICAL_THRESHOLD', defaultValue: '0',
            description: 'Fail pipeline if critical issues exceed this count')
        string(name: 'HIGH_THRESHOLD', defaultValue: '10',
            description: 'Fail pipeline if high issues exceed this count')
        string(name: 'SCAN_TIMEOUT_MINUTES', defaultValue: '120',
            description: 'Timeout in minutes for single wait-for attempt')
        string(name: 'WAIT_RETRIES', defaultValue: '2',
            description: 'Number of additional wait-for retry attempts (after the first one)')
        string(name: 'WAIT_RETRY_DELAY_MINUTES', defaultValue: '2',
            description: 'Minutes to wait between retries')
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

                    // Convertir umbrales a enteros
                    env.CRITICAL_THRESHOLD_INT = params.CRITICAL_THRESHOLD.toInteger()
                    env.HIGH_THRESHOLD_INT = params.HIGH_THRESHOLD.toInteger()
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
                    // Ruta fija opcional (instalación global en el nodo Jenkins)
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

        stage('FoD SAST Scan') {
            steps {
                script {
                    withCredentials([
                        string(credentialsId: 'iwa-fod-client-id', variable: 'FOD_CLIENT_ID'),
                        string(credentialsId: 'iwa-fod-client-secret', variable: 'FOD_CLIENT_SECRET')
                    ]) {
                        bat """
                            @echo off
                            echo [INFO] Logging into FoD...
                            "${env.FCLI_PATH}" fod session login --client-id "%FOD_CLIENT_ID%" --client-secret "%FOD_CLIENT_SECRET%" --url "${params.FOD_URL}" --fod-session jenkins

                            echo [INFO] Uploading artifact...
                            echo [DEBUG] Usando Release ID: ${params.FOD_RELEASE_ID}
                            "${env.FCLI_PATH}" fod sast-scan start --rel "${params.FOD_RELEASE_ID}" --file target\\iwa.jar --fod-session jenkins > scan_output.txt

                            echo [INFO] Logging out...
                            "${env.FCLI_PATH}" fod session logout --fod-session jenkins
                        """

                        def scanOutput = readFile('scan_output.txt')
                        echo "Scan Output:\n${scanOutput}"

                        // Extraer Scan ID
                        GLOBAL_SCAN_ID = extractScanId(scanOutput)
                        if (!GLOBAL_SCAN_ID) {
                            error "❌ No se pudo extraer el Scan ID del output"
                        }
                        echo "✅ Scan ID capturado: ${GLOBAL_SCAN_ID}"
                        currentBuild.displayName = "#${BUILD_NUMBER} - Scan ${GLOBAL_SCAN_ID}"
                    }
                }
            }
        }

        stage('Debug Scan ID') {
            steps {
                script {
                    echo "🛠 Debug Scan ID = ${GLOBAL_SCAN_ID ?: 'N/A'}"
                }
            }
        }

        stage('Wait for Scan Completion (with retries)') {
            steps {
                script {
                    if (!GLOBAL_SCAN_ID) {
                        error "❌ GLOBAL_SCAN_ID no definido. No se puede esperar la finalización del scan."
                    } else {
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
                                    "${env.FCLI_PATH}" fod session login --client-id "%FOD_CLIENT_ID%" --client-secret "%FOD_CLIENT_SECRET%" --url "${params.FOD_URL}" --fod-session jenkins

                                    echo [INFO] Waiting for scan ${GLOBAL_SCAN_ID}...
                                    "${env.FCLI_PATH}" fod sast-scan wait-for ${GLOBAL_SCAN_ID} --fod-session jenkins --timeout ${params.SCAN_TIMEOUT_MINUTES}m --output json > last-scan.json

                                    echo [INFO] Logging out...
                                    "${env.FCLI_PATH}" fod session logout --fod-session jenkins
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
                        }

                        if (!success) {
                            if (fileExists('last-scan.json')) {
                                echo "⚠ last-scan.json existe aunque wait falló; imprimiendo para debug:"
                                def raw = readFile('last-scan.json')
                                echo raw
                            }
                            error "❌ El scan no finalizó después de ${maxAttempts} intentos."
                        }
                    }
                }
            }
        }

        stage('Validate Scan Results') {
            steps {
                script {
                    if (!fileExists('last-scan.json')) {
                        error "❌ No se encontró last-scan.json con resultados."
                    } else {
                        def rawText = readFile('last-scan.json')
                        def jsonStart = rawText.indexOf('[')
                        if (jsonStart < 0) {
                            error "❌ No se encontró bloque JSON en last-scan.json"
                        }

                        def jsonText = rawText.substring(jsonStart).trim()
                        echo "📝 Bloque JSON detectado:"
                        echo jsonText

                        def scanResults = readJSON text: jsonText
                        def result = scanResults[0]
                        def status = result.analysisStatusType ?: 'Unknown'
                        def criticalCount = result.issueCountCritical ?: 0
                        def highCount = result.issueCountHigh ?: 0

                        echo "📊 SCAN RESULTS for ID: ${GLOBAL_SCAN_ID}"
                        echo "   Status: ${status}"
                        echo "   Critical Issues: ${criticalCount}"
                        echo "   High Issues: ${highCount}"
                        echo "   Threshold - Critical: ${params.CRITICAL_THRESHOLD}"
                        echo "   Threshold - High: ${params.HIGH_THRESHOLD}"

                        if (criticalCount > params.CRITICAL_THRESHOLD.toInteger()) {
                            error "❌ BUILD FAILED: Critical issues (${criticalCount}) > threshold (${params.CRITICAL_THRESHOLD})"
                        }
                        if (highCount > params.HIGH_THRESHOLD.toInteger()) {
                            error "❌ BUILD FAILED: High issues (${highCount}) > threshold (${params.HIGH_THRESHOLD})"
                        }

                        echo "✅ SCAN PASSED: Todos los umbrales cumplidos"
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                echo "📋 RESUMEN EJECUCIÓN"
                echo "   Status: ${currentBuild.currentResult}"
                echo "   Scan ID: ${GLOBAL_SCAN_ID ?: 'N/A'}"
                echo "   FoD Portal: ${params.FOD_URL}"

                if (fileExists('last-scan.json')) {
                    echo "===== last-scan.json (resumen) ====="
                    def txt = readFile('last-scan.json')
                    echo txt
                }

                cleanWs()
            }
        }

        success {
            echo "✅ PIPELINE EXITOSO"
        }

        failure {
            echo "❌ PIPELINE FALLIDO"
        }
    }
}

// Función para extraer Scan ID
def extractScanId(String output) {
    echo "Buscando Scan ID en el output..."
    if (!output) return null
    def lines = output.split('\\r?\\n')
    for (line in lines) {
        if (line?.toLowerCase()?.contains('static') && line?.toLowerCase()?.contains('pending')) {
            def parts = line.trim().split('\\s+')
            for (part in parts) {
                if (part?.isNumber() && part.length() >= 7) {
                    echo "✅ Scan ID encontrado: ${part}"
                    return part.toString().trim()
                }
            }
        }
    }
    def fallback = (output =~ /\b(\d{7,})\b/)
    if (fallback.find()) {
        echo "✅ Scan ID (fallback) encontrado: ${fallback.group(1)}"
        return fallback.group(1)
    }
    echo "❌ No se encontró Scan ID"
    return null
}
