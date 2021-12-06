@echo off
taskkill /F /T /im java.exe
timeout /t 2

if exist "C:\app-crawler\mercury-bootstrap-1.0.jar.publish" (
	move /Y "C:\app-crawler\mercury-bootstrap-1.0.jar" "C:\app-crawler\mercury-bootstrap-1.0.jar.latest"
	timeout /t 2
	move /Y "C:\app-crawler\mercury-bootstrap-1.0.jar.publish" "C:\app-crawler\mercury-bootstrap-1.0.jar"
	timeout /t 2
)

cd c:\app-crawler\
set JAR_PATH=.\mercury-bootstrap-1.0.jar
set MEM_OPTIONS=-Xms128m -Xms128m -Xss512k -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=128m -XX:MaxDirectMemorySize=256m -Dapollo.configService=http://db.f-li.cn:8080 -Denv=pro
java -javaagent:.\jmx_prometheus_javaagent.jar=8080:.\jmx_prometheus_config.yaml %MEM_OPTIONS% -Dspring.profiles.active=prd -Dfile.encoding=UTF-8 -jar %JAR_PATH%
