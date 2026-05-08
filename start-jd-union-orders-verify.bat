@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "ROOT_DIR=%~dp0"
set "JAVA_HOME=C:\Program Files\Java\jdk-21"
set "MAVEN_CMD=D:\home\apache-maven-3.9.12\bin\mvn.cmd"
set "HOSTS_FILE=%SystemRoot%\System32\drivers\etc\hosts"
set "TARGET_IP=192.168.31.4"

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [ERROR] JDK 21 not found: %JAVA_HOME%
  exit /b 1
)

if not exist "%MAVEN_CMD%" (
  set "MAVEN_CMD=mvn.cmd"
)

set "PATH=%JAVA_HOME%\bin;%PATH%"
set "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1"

call :ensure_host redis.internal
call :ensure_host ns.f-li.cn
call :ensure_host apollo.meta
call :ensure_host config.f-li.cn

if not exist "D:\app-crawler\data" mkdir "D:\app-crawler\data"
if not exist "D:\app-crawler\temp" mkdir "D:\app-crawler\temp"
if not exist "D:\app-crawler\data\jd-union\debug" mkdir "D:\app-crawler\data\jd-union\debug"

set "JVM_OPENS=--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED"
set "APP_ARGS=-Dapollo.config-service=http://%TARGET_IP%:8080 -Dapollo.meta=http://%TARGET_IP%:8080 -Denv=DEV -Dapp.redisUrl=%TARGET_IP%:6379 -Dapp.nameserverEndpoints=%TARGET_IP%:854 -Dapp.browser.downloadPath=D:/app-crawler/temp/ -Dapp.browser.diskDataPath=D:/app-crawler/data/chrome%%s/ -Dapp.browser.headless=false -Dapp.browser.playwrightChannel=chrome -Dapp.browser.humanInputEnabled=true -Dapp.custom.jdUnion.debugEnabled=true -Dapp.custom.jdUnion.debugOutputDir=D:/app-crawler/data/jd-union/debug -Dapp.custom.jdUnion.outputPath=D:/app-crawler/data/jd-union/orders-output.jsonl"

pushd "%ROOT_DIR%crawler-bootstrap" || exit /b 1
echo [INFO] JAVA_HOME=%JAVA_HOME%
echo [INFO] Maven=%MAVEN_CMD%
echo [INFO] Building classpath...
call "%MAVEN_CMD%" -q -DskipTests dependency:build-classpath -Dmdep.outputFile=target\classpath.txt
if not "%ERRORLEVEL%"=="0" (
  popd
  exit /b %ERRORLEVEL%
)

set "LAUNCH_ARGS=target\jd-union-orders-launch.args"
set /p DEPENDENCY_CP=<target\classpath.txt
(
  echo %JVM_OPENS%
  echo %APP_ARGS%
  echo -cp
  echo target\classes;%DEPENDENCY_CP%
  echo org.rx.crawler.Application
) > "%LAUNCH_ARGS%"

echo [INFO] Start app, then call the order API after login:
echo [INFO] POST http://127.0.0.1:8080/custom/jd-union/getPromotionOrders
echo [INFO] Body: {"startTime":"2026-04-15","endTime":"2026-05-08","debugEnabled":true}
echo [INFO] Running java directly to avoid spring-boot-maven-plugin class parsing issues.
java @%LAUNCH_ARGS%
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
