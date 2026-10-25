@echo off
chcp 65001 >nul
setlocal
title 推送到 GitHub
cd /d "%~dp0"

if not "%~1"=="" (
  set "URL=%~1"
) else (
  echo.
  echo   请输入你在 GitHub 上创建好的空仓库地址，例如：
  echo     https://github.com/你的用户名/漫画书架.git
  echo.
  set /p "URL=  地址: "
)

if "%URL%"=="" (
  echo.
  echo   没有输入地址，退出。
  pause
  exit /b 1
)

git rev-parse --git-dir >nul 2>nul
if errorlevel 1 (
  echo   当前目录不是 git 仓库，退出。
  pause
  exit /b 1
)

git remote remove origin >nul 2>nul
git remote add origin "%URL%"

echo.
echo   正在推送 main 分支到 %URL% ...
echo.
git push -u origin main

if errorlevel 1 (
  echo.
  echo   ------------------------------------------------------------
  echo   推送失败。常见原因：
  echo     1. 仓库还没创建：先去 https://github.com/new 建一个空仓库，
  echo        不要勾选 "Add a README"（否则会冲突）。
  echo     2. 登录窗口没弹出来或被关掉：到「Windows 凭据管理器」里删掉
  echo        git:https://github.com 这一条，再重新运行本脚本。
  echo     3. 地址写错：必须以 .git 结尾，用户名/仓库名要对。
  echo     4. 网络问题：这台机器上 github.com 被 hosts 指向 127.0.0.1，
  echo        靠本地代理转发，代理没开就会失败。
  echo   ------------------------------------------------------------
) else (
  echo.
  echo   推送成功！打开你的仓库页面即可看到。
  echo.
)

pause
