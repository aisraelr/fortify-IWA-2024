#!/usr/bin/env groovy

pipeline {
    agent any

    parameters {
        string(name: 'FOD_URL', defaultValue: 'https://api.ams.fortify.com',
            description: 'FoD API URL')
        string(name: 'FOD_RELEASE_ID', defaultValue: '1388854',
            description: 'FoD Release ID')
        string(name: 'CRITICAL_THRESHOLD', defaultValue: '10',
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
        FCLI_HOME = "${env.WORKSPACE}/fcli"
        SCAN_RESULTS = "${env.WORKSPACE}/sca-scan-results"
    }

    stages {
        stage('Prepare Environment') {
            when {
                expression { params.FOD_SCA == true }
            }
            steps {
                script {
                    // Crear carpetas
                    bat "if not exist ${FCLI_HOME} mkdir ${FCLI_HOME}"
                    bat "if not exist ${SCAN_RESULTS} mkdir ${SCAN_RESULTS}"

                    // Descargar fcli si no existe
                    if (!fileExists("${FCLI_HOME}\\fcli.exe")) {
                        echo "Downloading fcli..."
                        def fcliUrl = "https://github.com/fortify/fcli/releases/download/${params.FCLI_VERSION}/fcli.zip"
                        bat """
                            powershell -command "Invoke-WebRequest -Uri ${fcliUrl} -OutFile ${FCLI_HOME}\\fcli.zip"
                            powershell -command "Expand-Archive -Force ${FCLI_HOME}\\fcli.zip -DestinationPath ${FCLI_HOME}"
                        """
                    } else {
                        echo "fcli already exists in ${FCLI_HOME}"
                    }
                }
            }
        }


        stage('FoD Authentication') {
            when {
                expression { params.FOD_SCA == true }
            }
            steps {
                script {
                    withEnv([
                        "FOD_URL=${params.FOD_URL}",
                        "FOD_TENANT=${params.FOD_TENANT}",
                        "FOD_USER=${params.FOD_USER}",
                        "FOD_PASSWORD=${params.FOD_PASSWORD}"
                    ]) {
                        sh """
                            ${FCLI_HOME}/fcli fod session login --url $FOD_URL --tenant $FOD_TENANT --user $FOD_USER --password $FOD_PASSWORD
                        """
                    }
                }
            }
        }

        stage('Run SCA (Open Source Scan)') {
            when {
                expression { params.FOD_SCA == true }
            }
            steps {
                script {
                    sh """
                        ${FCLI_HOME}/fcli fod oss submit --release ${params.FOD_RELEASE_ID} --src ${env.WORKSPACE} --store ${SCAN_RESULTS}/sca-scan.json --wait
                    """
                }
            }
        }

        stage('Collect Results') {
            when {
                expression { params.FOD_SCA == true }
            }
            steps {
                script {
                    archiveArtifacts artifacts: 'sca-scan-results/*.json', fingerprint: true
                    echo "SCA scan results archived in sca-scan-results/"
                }
            }
        }
    }

    post {
        always {
            script {
                echo "📦 RESUMEN EJECUCIÓN"
                echo "   Status: ${currentBuild.currentResult}"
                echo "   Release ID: ${params.FOD_RELEASE_ID}"
                echo "   SCA Results: ${env.SCAN_RESULTS}/sca-scan.json"
            }
        }
    }
}
