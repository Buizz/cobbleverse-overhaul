@echo off
setlocal

set "REPO_ROOT=%~dp0"
set "CONTENT_MANAGER=%REPO_ROOT%tools\content-manager\content_manager.py"

where py >nul 2>nul
if %errorlevel% equ 0 (
    set "PYTHON_CMD=py -3"
) else (
    where python >nul 2>nul
    if errorlevel 1 (
        echo [ERROR] Python 3 was not found. Install Python 3 and try again.
        exit /b 1
    )
    set "PYTHON_CMD=python"
)

if "%~1"=="" goto help
if /I "%~1"=="validate" goto validate
if /I "%~1"=="validate-pack" goto validate_pack
if /I "%~1"=="api" goto api
if /I "%~1"=="test" goto test
goto help_error

:validate
%PYTHON_CMD% "%CONTENT_MANAGER%" validate --root "%REPO_ROOT%."
exit /b %errorlevel%

:validate_pack
%PYTHON_CMD% "%CONTENT_MANAGER%" validate --root "%REPO_ROOT%." --strict-pack
exit /b %errorlevel%

:api
%PYTHON_CMD% "%CONTENT_MANAGER%" api --root "%REPO_ROOT%."
exit /b %errorlevel%

:test
%PYTHON_CMD% -m unittest discover -s "%REPO_ROOT%tools\content-manager\tests" -p "test_*.py"
exit /b %errorlevel%

:help_error
echo [ERROR] Unknown command: %~1

:help
echo Usage: build.bat ^<command^>
echo.
echo   validate       Validate dependency lock and normalized content
echo   validate-pack  Validate that dependencies are ready for CurseForge packaging
echo   api            Start the local content manager Web API
echo   test           Run content manager unit tests
exit /b 1
