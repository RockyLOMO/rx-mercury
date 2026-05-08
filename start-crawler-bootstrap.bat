@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "ROOT_DIR=%~dp0"
set "JAVA_HOME=C:\Program Files\Java\jdk-17"
set "MAVEN_CMD=D:\home\apache-maven-3.9.12\bin\mvn.cmd"
set "HOSTS_FILE=%SystemRoot%\System32\drivers\etc\hosts"
set "TARGET_IP=192.168.31.4"

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [ERROR] JDK 17 not found: %JAVA_HOME%
  exit /b 1
)

if not exist "%MAVEN_CMD%" (
  set "MAVEN_CMD=mvn.cmd"
)

set "PATH=%JAVA_HOME%\bin;%PATH%"

call :ensure_host redis.internal
call :ensure_host ns.f-li.cn
call :ensure_host apollo.meta
call :ensure_host config.f-li.cn

if not exist "D:\app-crawler\driver" mkdir "D:\app-crawler\driver"
if not exist "D:\app-crawler\data" mkdir "D:\app-crawler\data"
if not exist "D:\app-crawler\temp" mkdir "D:\app-crawler\temp"

set "JVM_OPENS=--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED"
set "APP_ARGS=-Dapollo.config-service=http://%TARGET_IP%:8080 -Dapollo.meta=http://%TARGET_IP%:8080 -Denv=DEV -Dapp.redisUrl=%TARGET_IP%:6379 -Dapp.nameserverEndpoints=%TARGET_IP%:854 -Dapp.chromeDriver=D:/app-crawler/driver/chromedriver.exe -Dapp.browser.downloadPath=D:/app-crawler/temp/ -Dapp.browser.diskDataPath=D:/app-crawler/data/chrome%%s/ -Dapp.browser.headless=true"

pushd "%ROOT_DIR%crawler-bootstrap" || exit /b 1
echo [INFO] JAVA_HOME=%JAVA_HOME%
echo [INFO] Maven=%MAVEN_CMD%

set "LAUNCH_METHOD=1"
if "%~1"=="jar" (
  set "LAUNCH_METHOD=2"
) else if "%~1"=="mvn" (
  set "LAUNCH_METHOD=1"
) else (
  echo Select launch method:
  echo [1] Maven (spring-boot:run) - default
  echo [2] Jar (package first, then java -jar)
  choice /c 12 /t 5 /d 1 /m "Make your choice (auto-select [1] in 5s): "
  set "LAUNCH_METHOD=!ERRORLEVEL!"
)

if "!LAUNCH_METHOD!"=="2" (
  echo [INFO] Packaging application (mvn clean package -Dmaven.test.skip=true)...
  call "%MAVEN_CMD%" clean package -Dmaven.test.skip=true
  if !ERRORLEVEL! neq 0 (
    echo [ERROR] Build failed!
    popd
    exit /b !ERRORLEVEL!
  )
  echo [INFO] Starting crawler-bootstrap via JAR...
  java %JVM_OPENS% %APP_ARGS% -jar target\crawler-bootstrap-1.0.jar
) else (
  echo [INFO] Starting crawler-bootstrap via Maven...
  call "%MAVEN_CMD%" "-Dspring-boot.run.jvmArguments=%JVM_OPENS% %APP_ARGS%" spring-boot:run
)
set "EXIT_CODE=%ERRORLEVEL%"
popd
exit /b %EXIT_CODE%

:ensure_host
set "HOST_NAME=%~1"
findstr /R /C:"^[ 	]*%TARGET_IP%[ 	][ 	]*%HOST_NAME%\>" "%HOSTS_FILE%" >nul 2>&1
if "%ERRORLEVEL%"=="0" (
  echo [INFO] hosts exists: %HOST_NAME% -^> %TARGET_IP%
  exit /b 0
)

net session >nul 2>&1
if not "%ERRORLEVEL%"=="0" (
  echo [WARN] hosts missing: %HOST_NAME% -^> %TARGET_IP%
  echo [WARN] Run this script as Administrator to auto append hosts entry.
  exit /b 0
)

>> "%HOSTS_FILE%" echo %TARGET_IP% %HOST_NAME%
echo [INFO] hosts added: %HOST_NAME% -^> %TARGET_IP%
exit /b 0
