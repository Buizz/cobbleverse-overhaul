@echo off
setlocal

set "REPO_ROOT=%~dp0"
set "CONTENT_MANAGER=%REPO_ROOT%tools\content-manager\content_manager.py"
set "PACK_BUILDER=%REPO_ROOT%tools\pack-builder\pack_builder.py"
set "SMOKE_PROFILE=pack\profiles\import-smoke.json"

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
if /I "%~1"=="pack-smoke" goto pack_smoke
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
if errorlevel 1 exit /b %errorlevel%
%PYTHON_CMD% -m unittest discover -s "%REPO_ROOT%tools\pack-builder\tests" -p "test_*.py"
exit /b %errorlevel%

:pack_smoke
%PYTHON_CMD% "%PACK_BUILDER%" build --root "%REPO_ROOT%." --profile "%SMOKE_PROFILE%"
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
echo   pack-smoke     Build a minimal CurseForge import test ZIP
exit /b 1
