@echo off
setlocal
set "ROOT=%~dp0"
set "JAVA_HOME=%ROOT%.tools\jdk-25.0.5+8"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo [HandheldMusic] JAVA_HOME = %JAVA_HOME%
echo [HandheldMusic] 启动开发客户端...
call "%ROOT%gradlew.bat" runClient %*
