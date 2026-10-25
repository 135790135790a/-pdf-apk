@echo off
chcp 65001 >nul
title 允许手机访问漫画书架
cd /d "%~dp0"

net session >nul 2>&1
if errorlevel 1 (
  echo 需要管理员权限，正在申请（会弹 UAC，点"是"）...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

netsh advfirewall firewall delete rule name="MangaShelf Web" >nul 2>&1
netsh advfirewall firewall add rule name="MangaShelf Web" dir=in action=allow protocol=TCP localport=8971-8980 profile=private,domain >nul

if errorlevel 1 (
  echo.
  echo   添加防火墙规则失败，请手动在"Windows 安全中心 - 防火墙"里放行 8971 端口。
  echo.
) else (
  echo.
  echo   已放行 8971-8980 端口（仅"专用网络"和"域网络"，公共 Wi-Fi 不开放）。
  echo.
  echo   下一步：
  echo     1. 手机连到和这台电脑同一个 Wi-Fi
  echo     2. 打开 打开漫画书架.lnk（或 web\start.bat）
  echo     3. 手机浏览器访问命令行窗口里显示的 "手机上：http://...:8971/"
  echo     4. 手机浏览器菜单里选"添加到主屏幕"，以后一键打开
  echo.
)

pause
