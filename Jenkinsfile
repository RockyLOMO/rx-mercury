pipeline {
    agent {
        node {
            label 'maven-jdk-11'
        }
    }

    environment {
        APP_NAME = 'mercury-bootstrap'
        JAR_NAME = 'mercury-bootstrap-1.0.jar'
        APP_HOST = 'crawler.f-li.cn'
        REMOTE_PATH = '/c/app-crawler/'
        HEALTH_URL = 'https://crawler.f-li.cn/health'
        U0 = 'jks'
    }

    stages {
        stage('Build') {
            steps {
                container ('maven-jdk-11') {
                    sh 'mvn -B clean package -pl ${APP_NAME} -am -Dmaven.test.skip=true'
                    sh 'cp -f ${PWD}/${APP_NAME}/target/${JAR_NAME} ${PWD}/${JAR_NAME}'
                }
            }
        }

        stage('Deploy') {
            steps {
                container ('maven-jdk-11') {
                    sh '/home/scpx.sh deploy/jmx/${APP_NAME}_start.bat ${APP_HOST} ${REMOTE_PATH}start.bat ${U0}'
                    sh '/home/scpx.sh deploy/jmx/${APP_NAME}_rollback.bat ${APP_HOST} ${REMOTE_PATH}rollback.bat ${U0}'
                    
                    sh '/home/scpx.sh jmx_prometheus_config.yaml ${APP_HOST} ${REMOTE_PATH}jmx_prometheus_config.yaml ${U0}'
                    sh '/home/scpx.sh jmx_prometheus_javaagent.jar ${APP_HOST} ${REMOTE_PATH}jmx_prometheus_javaagent.jar ${U0}'
                    sh '/home/scpx.sh ${JAR_NAME} ${APP_HOST} ${REMOTE_PATH}${JAR_NAME}.publish ${U0}'
                    script {
                        sleep(2)
                    }
                    sh '/home/sshx_w.sh ${APP_HOST} "schtasks /end /tn javabg & schtasks /run /tn javabg"'
                    script {
                        sleep(30)

                        def publishOk = checkHealth(env.HEALTH_URL)
                        if (publishOk) {
                            println("Publish ok!")
                        } else {
                            sh '/home/sshx_w.sh ${APP_HOST} "schtasks /end /tn javabgrb & schtasks /run /tn javabgrb"'
                            currentBuild.result = "FAILURE"
                            throw new RuntimeException("Health check fail, and restore")
                        }
                    }
                }
            }
        }
    }
}

def checkHealth(healthUrl) {
    def seconds = 120
    def sleepSeconds = 6
    def count = seconds / sleepSeconds
    def publishOk = false
    while(count > 0) {
        sleep(sleepSeconds)
        try{
            def health = sh(script: "curl " + healthUrl + " -k", returnStdout: true).trim()
            println(health)
            if (health == "ok") {
                publishOk = true
                break
            }
        }
        catch(e){
            println("ignore error: " + e)
        }
        println("Check health fail, retry count = " + count)
        count--
    }
    return publishOk
}
