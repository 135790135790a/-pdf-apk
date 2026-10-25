@echo off
chcp 65001 >nul
setlocal
title 漫画书架 · 本地网页版
cd /d "%~dp0"

set "PYCMD="

python -c "print(1)" >nul 2>nul
if not errorlevel 1 set "PYCMD=python"

if not defined PYCMD (
  py -3 -c "print(1)" >nul 2>nul
  if not errorlevel 1 set "PYCMD=py -3"
)

if not defined PYCMD (
  for /d %%D in ("%LOCALAPPDATA%\Programs\Python\Python3*") do (
    if exist "%%~fD\python.exe" set "PYCMD=%%~fD\python.exe"
  )
)

if not defined PYCMD goto nopython

%PYCMD% server.py
if errorlevel 1 pause
goto :eof

:nopython
echo.
echo   没有找到可用的 Python 3
echo.
echo   请到 python.org 下载安装 Python 3.9 以上版本，
echo   安装时记得勾选 "Add python.exe to PATH"，装好后再双击本文件。
echo.
pause
