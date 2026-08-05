@echo off
setlocal

set "REPO_ROOT=%~dp0"
set "CONTENT_MANAGER=%REPO_ROOT%tools\content-manager\content_manager.py"
set "PACK_BUILDER=%REPO_ROOT%tools\pack-builder\pack_builder.py"
set "DATA_MOD_BUILDER=%REPO_ROOT%tools\mod-builder\build_data_mod.py"
set "GRADLEW=%REPO_ROOT%projects\cobbleventure-battle-ai\gradlew.bat"
set "WORLD_BOOTSTRAP_PROJECT=%REPO_ROOT%projects\cobbleventure-world-bootstrap"
set "SMOKE_PROFILE=pack\profiles\import-smoke.json"
set "DEVELOPMENT_PROFILE=pack\profiles\development-placeholder.json"

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
if /I "%~1"=="web" goto api
if /I "%~1"=="api" goto api
if /I "%~1"=="test" goto test
if /I "%~1"=="generate" goto generate
if /I "%~1"=="mod-bootstrap" goto mod_bootstrap
if /I "%~1"=="pack-smoke" goto pack_smoke
if /I "%~1"=="pack" goto pack
if /I "%~1"=="pack-release" goto pack_release
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
if errorlevel 1 exit /b %errorlevel%
%PYTHON_CMD% -m unittest discover -s "%REPO_ROOT%tools\mod-builder\tests" -p "test_*.py"
if errorlevel 1 exit /b %errorlevel%
call "%GRADLEW%" -p "%WORLD_BOOTSTRAP_PROJECT%" test
exit /b %errorlevel%

:generate
%PYTHON_CMD% "%CONTENT_MANAGER%" generate --root "%REPO_ROOT%."
exit /b %errorlevel%

:mod_bootstrap
%PYTHON_CMD% "%DATA_MOD_BUILDER%" --root "%REPO_ROOT%."
if errorlevel 1 exit /b %errorlevel%
call "%GRADLEW%" -p "%WORLD_BOOTSTRAP_PROJECT%" build
exit /b %errorlevel%

:pack_smoke
%PYTHON_CMD% "%PACK_BUILDER%" build --root "%REPO_ROOT%." --profile "%SMOKE_PROFILE%"
exit /b %errorlevel%

:pack
%PYTHON_CMD% "%CONTENT_MANAGER%" validate --root "%REPO_ROOT%."
if errorlevel 1 exit /b %errorlevel%
%PYTHON_CMD% "%DATA_MOD_BUILDER%" --root "%REPO_ROOT%."
if errorlevel 1 exit /b %errorlevel%
call "%GRADLEW%" -p "%WORLD_BOOTSTRAP_PROJECT%" build
if errorlevel 1 exit /b %errorlevel%
%PYTHON_CMD% "%PACK_BUILDER%" build --root "%REPO_ROOT%." --profile "%DEVELOPMENT_PROFILE%"
exit /b %errorlevel%

:pack_release
%PYTHON_CMD% "%CONTENT_MANAGER%" validate --root "%REPO_ROOT%." --strict-pack
if errorlevel 1 (
    echo.
    echo [INFO] Release packaging was blocked because the dependency lock is not ready.
    exit /b 1
)
echo [ERROR] Release manifest export is not implemented yet.
exit /b 1

:help_error
echo [ERROR] Unknown command: %~1

:help
echo Usage: build.bat ^<command^>
echo.
echo   validate       Validate dependency lock and normalized content
echo   validate-pack  Validate that dependencies are ready for CurseForge packaging
echo   web            Start the local content manager Web UI and API
echo   api            Alias for web (kept for compatibility)
echo   test           Run Python tests and compile the world bootstrap module
echo   generate       Generate RCT trainers and in-game AI runtime profiles
echo   mod-bootstrap  Build the starter-town NeoForge Java mod JAR
echo   pack-smoke     Build a minimal CurseForge import test ZIP
echo   pack           Build the temporary development CurseForge ZIP
echo   pack-release   Validate release readiness; blocked until dependencies are locked
exit /b 1
