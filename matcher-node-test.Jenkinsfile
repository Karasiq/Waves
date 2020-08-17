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
        SBT_THREAD_NUMBER = 6
    }
    stages {
        stage('Cleanup') {
            steps {
                sh 'find ~/.sbt/1.0/staging/*/waves -type d -name target | xargs -I{} rm -rf {}'
                sh 'find . -type d -name target | xargs -I{} rm -rf {}'
                sh 'docker rmi `docker images --format "{{.Repository}}:{{.Tag}}" | grep -E "wavesplatform|wvservices"` || true'
                sh 'docker system prune -f || true'
            }
        }
        stage('Unit Tests') {
            steps {
                sh 'sbt "lang/compile;checkPRRaw"' // HACK lang/compile to build without protoc errors
            }
        }
        stage('Integration Tests') {
            steps {
                sh 'sbt node-it/test'
            }
        }
        stage ('Build artifacts') {
            steps {
                build job: 'Waves.Exchange/Matcher/Matcher Node - Patched - Build', propagate: false, wait: false, parameters: [
                  [$class: 'StringParameterValue', name: 'BRANCH_NAME', value: "${BRANCH_NAME}"]
                ]
            }
        }
    }
    post {
        always {
            sh 'tar -czf node.logs.tar.gz node-it/target/logs'
            archiveArtifacts artifacts: 'node.logs.tar.gz', fingerprint: true
            junit '**/test-reports/*.xml'
        }
        cleanup {
            cleanWs()
        }
    }
}
