@echo off
rem Starts Filemat from the portable zip. Configure it with FM_* environment variables (docs\windows.md).
rem Arguments are passed on, for example: filemat.cmd --server.port=9090
setlocal

set "FILEMAT_HOME=%~dp0"
if not defined FM_FFMPEG_PATH set "FM_FFMPEG_PATH=%FILEMAT_HOME%ffmpeg\ffmpeg.exe"

rem Print UTF-8 so that file names aren't garbled. "Active code page: 437" or "Aktive Codepage: 850."
set "PREVIOUS_CODE_PAGE="
for /f "tokens=2 delims=:." %%c in ('chcp') do set "PREVIOUS_CODE_PAGE=%%c"

rem Java finds its runtime through the ANSI code page, so it can't start from a folder with letters missing from it
rem (for example Chinese letters on an English Windows). Echo the folder in that code page to see if it survives.
set "ANSI_CODE_PAGE="
for /f "tokens=3" %%a in ('reg query HKLM\SYSTEM\CurrentControlSet\Control\Nls\CodePage /v ACP 2^>nul') do set "ANSI_CODE_PAGE=%%a"
if defined ANSI_CODE_PAGE chcp %ANSI_CODE_PAGE% >nul 2>nul
set "FILEMAT_HOME_ANSI="
for /f "delims=" %%p in ('cmd /d /v:on /c "echo(!FILEMAT_HOME!"') do set "FILEMAT_HOME_ANSI=%%p"
chcp 65001 >nul
rem No parentheses block: a folder like "Program Files (x86)" would end it early
if "%FILEMAT_HOME_ANSI%"=="%FILEMAT_HOME%" goto start
echo WARNING: Java can't start from a folder with letters that Windows' language settings don't cover:
echo   "%FILEMAT_HOME%"
echo Move Filemat to a folder with plain letters, for example C:\Apps\filemat.
echo.

:start

"%FILEMAT_HOME%jre\bin\java.exe" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%FILEMAT_HOME%filemat.jar" %*
set "EXIT_CODE=%ERRORLEVEL%"

rem After Ctrl+C, cmd asks "Terminate batch job (Y/N)?". Y ends this file here, and cmd can't run anything then,
rem so the console keeps code page 65001. N carries on to this line. Nothing in a batch file can answer for the user.
if defined PREVIOUS_CODE_PAGE chcp %PREVIOUS_CODE_PAGE: =% >nul
exit /b %EXIT_CODE%
