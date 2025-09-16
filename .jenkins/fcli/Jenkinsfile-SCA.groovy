#!/usr/bin/env groovy

pipeline {
    agent any

    parameters {
        booleanParam(name: 'FOD_SCA', defaultValue: true,
            description: 'Run Fortify on Demand SCA (Open Source Scan) using fcli')
        string(name: 'FOD_URL', defaultValue: 'https://api.ams.fortify.com', 
            description: 'FoD API URL')
        string(name: 'FOD_TENANT', defaultValue: '', 
            description: 'FoD Tenant ID')
        string(name: 'FOD_USER', defaultValue: '', 
            description: 'FoD Username (email)')
        password(name: 'FOD_PASSWORD', defaultValue: '', 
            description: 'FoD Password')
        string(name: 'FOD_RELEASE_ID', defaultValue: '', 
            description: 'FoD Release ID to associate scan with')
        string(name: 'FCLI_VERSION', defaultValue: 'latest',
            description: 'Version of Fortify CLI (fcli) to use')
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
