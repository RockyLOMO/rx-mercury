@echo off
call "%~dp0start-crawler-bootstrap.bat" jd-union-orders-verify %*
exit /b %ERRORLEVEL%
