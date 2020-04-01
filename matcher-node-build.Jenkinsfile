pipeline {
    agent {
        label 'buildagent-matcher'
    }
    options {
        ansiColor('xterm')
    }
    environment {
        SBT_HOME = tool name: 'sbt-1.2.6', type: 'org.jvnet.hudson.plugins.SbtPluginBuilder$SbtInstallation'
        SBT_OPTS = '-Xmx40g -XX:ReservedCodeCacheSize=512m'
        PATH = "${env.SBT_HOME}/bin:${env.PATH}"
    }
    stages {
        stage('Cleanup') {
            steps {
                script {
                    currentBuild.displayName = "${params.BRANCH_NAME}"
                }
                sh 'find ~/.sbt/1.0/staging/*/waves -type d -name target | xargs -I{} rm -rf {}'
                sh 'find . -type d -name target | xargs -I{} rm -rf {}'
                sh 'docker rmi `docker images --format "{{.Repository}}:{{.Tag}}" | grep -E "wavesplatform|wvservices"` || true'
                sh 'docker system prune -f || true'
            }
        }
        stage('Determining Version') {
            steps {
                script {
                    MATCHER_NODE_VERSION = sh(script: 'sbt -warn "print node/version" | tail -n 1', returnStdout: true).trim().replaceFirst(/^(\d+\.\d+\.\d+(\.\d+)?(-\d+)?).*$/, '$1').replace('-', '.')
                }
            }
        }
        stage('Assembly') {
            steps {
                sh 'sbt -Dnetwork=mainnet "packageAll"' // to copy artifacts to local cache
                sh 'sbt -Dnetwork=testnet "node/assembly;node/Debian/packageBin"'
                sh 'sbt -Dnetwork=stagenet "node/assembly;node/Debian/packageBin"'
                sh 'sbt -Dnetwork=devnet "node/assembly;node/Debian/packageBin"'
            }
        }
        stage('Build & Push Docker Image') {
            steps {
                sh "docker build -f dockerfile.patched.docker -t registry.wvservices.com/waves/dex/wavesnode:${MATCHER_NODE_VERSION} -t registry.wvservices.com/waves/dex/wavesnode:latest ."
                sh """
                    docker push registry.wvservices.com/waves/dex/wavesnode:${MATCHER_NODE_VERSION} 
                    docker push registry.wvservices.com/waves/dex/wavesnode:latest
                """
            }
        }
    }
    post {
        success {
            archiveArtifacts artifacts: 'node/target/waves-all-*.jar, node/target/waves*_all.deb', fingerprint: true
        }
        cleanup {
            cleanWs()
        }
    }
}
