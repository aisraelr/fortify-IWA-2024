#!/usr/bin/env groovy

pipeline {
    agent any

    parameters {
        // === Parámetros homologados con SAST ===
        booleanParam(name: 'FOD_SCA', defaultValue: true,
            description: 'Run Fortify on Demand SCA (OSS) scan using fcli')
        string(name: 'FOD_URL', defaultValue: 'https://api.ams.fortify.com',
            description: 'FoD API URL')
        string(name: 'FOD_TENANT', defaultValue: '',
            description: 'FoD tenant name')
        string(name: 'FOD_USER', defaultValue: '',
            description: 'FoD user name')
        password(name: 'FOD_PASSWORD', defaultValue: '',
            description: 'FoD password or PAT')
        string(name: 'FOD_RELEASE_ID', defaultValue: '',
            description: 'FoD Release ID for the application to scan')

        // === Parámetros adicionales solo para OSS/SCA ===
        string(name: 'BUILD_TOOL', defaultValue: 'maven',
            description: 'Build tool for dependency resolution (maven/gradle/npm/yarn)')
        string(name: 'SCA_OUTPUT', defaultValue: 'sca-results.json',
            description: 'File where OSS scan results will be exported')
    }

    environment {
        FCLI_HOME = "${env.WORKSPACE}/fcli"
    }

    stages {
        stage('Setup fcli') {
            steps {
                script {
                    if (!fileExists("${FCLI_HOME}/fcli")) {
                        echo "Descargando Fortify CLI (fcli)..."
                        sh """
                            mkdir -p ${FCLI_HOME}
                            curl -sL https://github.com/fortify/fcli/releases/latest/download/fcli-linux.tgz | tar xz -C ${FCLI_HOME}
                        """
                    } else {
                        echo "fcli ya existe en ${FCLI_HOME}, usando caché local."
                    }
                }
            }
        }

        stage('Login to FoD') {
            steps {
                script {
                    sh """
                        ${FCLI_HOME}/fcli fod session login \
                            --url ${params.FOD_URL} \
                            --tenant ${params.FOD_TENANT} \
                            --user ${params.FOD_USER} \
                            --password '${params.FOD_PASSWORD}' \
                            --session sca-session
                    """
                }
            }
        }

        stage('FoD SCA (OSS) Scan') {
            when {
                expression { params.FOD_SCA == true }
            }
            steps {
                script {
                    sh """
                        ${FCLI_HOME}/fcli fod oss scan start \
                            --release ${params.FOD_RELEASE_ID} \
                            --build-tool ${params.BUILD_TOOL} \
                            --output-file ${params.SCA_OUTPUT} \
                            --session sca-session \
                            --wait
                    """
                }
            }
        }

        stage('Export OSS Results') {
            steps {
                script {
                    sh """
                        echo "Exportando resultados OSS a ${params.SCA_OUTPUT}"
                        cat ${params.SCA_OUTPUT} || echo "No se generó archivo de resultados."
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
