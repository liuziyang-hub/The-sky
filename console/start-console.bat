@echo off
cd /d "%~dp0"
echo Starting Realtime Debug Console
echo   LAN mode : same Wi-Fi, connect by UID
echo   USB mode : adb forward tcp:17890 (cross-network)
python server.py
pause
