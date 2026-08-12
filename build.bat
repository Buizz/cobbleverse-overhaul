@echo off
setlocal

set "REPO_ROOT=%~dp0"
set "CONTENT_MANAGER=%REPO_ROOT%tools\content-manager\content_manager.py"
set "STOP_CONTENT_MANAGER=%REPO_ROOT%tools\content-manager\stop_existing_server.ps1"
set "PACK_BUILDER=%REPO_ROOT%tools\pack-builder\pack_builder.py"
set "DATA_MOD_BUILDER=%REPO_ROOT%tools\mod-builder\build_data_mod.py"
set "CUSTOM_SPAWN_BUILDER=%REPO_ROOT%tools\cobblemon-custom-spawns\build_custom_spawns.py"
set "TRAINER_SKIN_BUILDER=%REPO_ROOT%tools\content-manager\skin-pipeline\assemble_skin.py"
set "YOUNGSTER_SKIN_MANIFEST=%REPO_ROOT%tools\content-manager\skin-pipeline\work\youngster\manifest.json"
set "EASY_NPC_PRESET_BUILDER=%REPO_ROOT%tools\content-manager\generate_easy_npc_presets.py"
set "KANTO_GYM_LEADER_BUILDER=%REPO_ROOT%tools\content-manager\generate_kanto_gym_leaders.py"
set "GRADLEW=%REPO_ROOT%projects\cobbleventure-battle-ai\gradlew.bat"
set "WORLD_BOOTSTRAP_PROJECT=%REPO_ROOT%projects\cobbleventure-world-bootstrap"
set "PLAYER_MENU_PROJECT=%REPO_ROOT%projects\cobbleventure-player-menu"
set "STRUCTURE_BUILDER_PROJECT=%REPO_ROOT%projects\cobbleventure-structure-builder"
set "STRUCTURE_BUILDER_TOOL=%REPO_ROOT%tools\structure-builder\structure_builder.py"
set "MUSIC_PACK_BUILDER=%REPO_ROOT%tools\music-catalog\music_catalog.py"
set "SMOKE_PROFILE=pack\profiles\import-smoke.json"
set "DEVELOPMENT_PROFILE=pack\profiles\development-placeholder.json"
set "STRUCTURE_BUILDER_PROFILE=pack\profiles\structure-builder.json"

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
if /I "%~1"=="spawns" goto spawns
if /I "%~1"=="music" goto music
if /I "%~1"=="mod-bootstrap" goto mod_bootstrap
if /I "%~1"=="mod-menu" goto mod_menu
if /I "%~1"=="pack-smoke" goto pack_smoke
if /I "%~1"=="pack" goto pack
if /I "%~1"=="pack-release" goto pack_release
if /I "%~1"=="builder-world" goto builder_world
if /I "%~1"=="builder-import" goto builder_import
goto help_error

:validate
%PYTHON_CMD% "%CONTENT_MANAGER%" validate --root "%REPO_ROOT%."
exit /b %errorlevel%

:validate_pack
%PYTHON_CMD% "%CONTENT_MANAGER%" validate --root "%REPO_ROOT%." --strict-pack
exit /b %errorlevel%

:api
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%STOP_CONTENT_MANAGER%" -ManagerPath "%CONTENT_MANAGER%" -RepositoryRoot "%REPO_ROOT%."
if errorlevel 1 (
    echo [ERROR] Failed to stop the previous content manager server.
    exit /b %errorlevel%
)
%PYTHON_CMD% "%CONTENT_MANAGER%" api --root "%REPO_ROOT%."
exit /b %errorlevel%

:test
%PYTHON_CMD% -m unittest discover -s "%REPO_ROOT%tools\content-manager\tests" -p "test_*.py"
if errorlevel 1 exit /b %errorlevel%
%PYTHON_CMD% -m unittest discover -s "%REPO_ROOT%tools\pack-builder\tests" -p "test_*.py"
if errorlevel 1 exit /b %errorlevel%
%PYTHON_CMD% -m unittest discover -s "%REPO_ROOT%tools\mod-builder\tests" -p "test_*.py"
if errorlevel 1 exit /b %errorlevel%
%PYTHON_CMD% -m unittest discover -s "%REPO_ROOT%tools\cobblemon-custom-spawns\tests" -p "test_*.py"
if errorlevel 1 exit /b %errorlevel%
call "%GRADLEW%" -p "%WORLD_BOOTSTRAP_PROJECT%" test
if errorlevel 1 exit /b %errorlevel%
call "%GRADLEW%" -p "%PLAYER_MENU_PROJECT%" test
if errorlevel 1 exit /b %errorlevel%
%PYTHON_CMD% -m unittest discover -s "%REPO_ROOT%tools\structure-builder\tests" -p "test_*.py"
if errorlevel 1 exit /b %errorlevel%
call "%GRADLEW%" -p "%STRUCTURE_BUILDER_PROJECT%" test
exit /b %errorlevel%

:generate
%PYTHON_CMD% "%KANTO_GYM_LEADER_BUILDER%"
if errorlevel 1 exit /b %errorlevel%
%PYTHON_CMD% "%CONTENT_MANAGER%" generate --root "%REPO_ROOT%."
if errorlevel 1 exit /b %errorlevel%
%PYTHON_CMD% "%TRAINER_SKIN_BUILDER%" "%YOUNGSTER_SKIN_MANIFEST%"
if errorlevel 1 exit /b %errorlevel%
%PYTHON_CMD% "%EASY_NPC_PRESET_BUILDER%"
exit /b %errorlevel%

:spawns
%PYTHON_CMD% "%CUSTOM_SPAWN_BUILDER%" --root "%REPO_ROOT%."
exit /b %errorlevel%

:music
%PYTHON_CMD% "%MUSIC_PACK_BUILDER%" --root "%REPO_ROOT%."
exit /b %errorlevel%

:mod_bootstrap
%PYTHON_CMD% "%KANTO_GYM_LEADER_BUILDER%"
if errorlevel 1 exit /b %errorlevel%
%PYTHON_CMD% "%CONTENT_MANAGER%" generate --root "%REPO_ROOT%."
if errorlevel 1 exit /b %errorlevel%
%PYTHON_CMD% "%TRAINER_SKIN_BUILDER%" "%YOUNGSTER_SKIN_MANIFEST%"
if errorlevel 1 exit /b %errorlevel%
%PYTHON_CMD% "%EASY_NPC_PRESET_BUILDER%"
if errorlevel 1 exit /b %errorlevel%
%PYTHON_CMD% "%DATA_MOD_BUILDER%" --root "%REPO_ROOT%."
if errorlevel 1 exit /b %errorlevel%
call "%GRADLEW%" -p "%WORLD_BOOTSTRAP_PROJECT%" build
exit /b %errorlevel%

:mod_menu
call "%GRADLEW%" -p "%PLAYER_MENU_PROJECT%" build
exit /b %errorlevel%

:pack_smoke
%PYTHON_CMD% "%PACK_BUILDER%" build --root "%REPO_ROOT%." --profile "%SMOKE_PROFILE%"
exit /b %errorlevel%

:pack
%PYTHON_CMD% "%CONTENT_MANAGER%" validate --root "%REPO_ROOT%."
if errorlevel 1 exit /b %errorlevel%
%PYTHON_CMD% "%CONTENT_MANAGER%" generate --root "%REPO_ROOT%."
if errorlevel 1 exit /b %errorlevel%
%PYTHON_CMD% "%CUSTOM_SPAWN_BUILDER%" --root "%REPO_ROOT%."
if errorlevel 1 exit /b %errorlevel%
%PYTHON_CMD% "%TRAINER_SKIN_BUILDER%" "%YOUNGSTER_SKIN_MANIFEST%"
if errorlevel 1 exit /b %errorlevel%
%PYTHON_CMD% "%EASY_NPC_PRESET_BUILDER%"
if errorlevel 1 exit /b %errorlevel%
%PYTHON_CMD% "%MUSIC_PACK_BUILDER%" --root "%REPO_ROOT%."
if errorlevel 1 exit /b %errorlevel%
%PYTHON_CMD% "%DATA_MOD_BUILDER%" --root "%REPO_ROOT%."
if errorlevel 1 exit /b %errorlevel%
call "%GRADLEW%" -p "%WORLD_BOOTSTRAP_PROJECT%" build
if errorlevel 1 exit /b %errorlevel%
call "%GRADLEW%" -p "%PLAYER_MENU_PROJECT%" build
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

:builder_world
%PYTHON_CMD% "%STRUCTURE_BUILDER_TOOL%" --root "%REPO_ROOT%." generate
if errorlevel 1 exit /b %errorlevel%
call "%GRADLEW%" -p "%STRUCTURE_BUILDER_PROJECT%" build --no-configuration-cache
if errorlevel 1 exit /b %errorlevel%
call "%GRADLEW%" -p "%STRUCTURE_BUILDER_PROJECT%" syncBuilderWorld --no-configuration-cache
if errorlevel 1 exit /b %errorlevel%
%PYTHON_CMD% "%PACK_BUILDER%" build --root "%REPO_ROOT%." --profile "%STRUCTURE_BUILDER_PROFILE%"
exit /b %errorlevel%

:builder_import
if "%~2"=="" (
    echo [ERROR] Builder world path is required.
    echo Usage: build.bat builder-import "^<CurseForge instance^>\saves\Cobbleventure Structure Builder"
    exit /b 1
)
%PYTHON_CMD% "%STRUCTURE_BUILDER_TOOL%" --root "%REPO_ROOT%." import "%~2"
exit /b %errorlevel%

:help_error
echo [ERROR] Unknown command: %~1

:help
echo Usage: build.bat ^<command^>
echo.
echo   validate       Validate dependency lock and normalized content
echo   validate-pack  Validate that dependencies are ready for CurseForge packaging
echo   web            Start the local content manager Web UI and API
echo   api            Alias for web (kept for compatibility)
echo   test           Run Python tests and compile the NeoForge modules
echo   generate       Generate RCT trainers and in-game AI runtime profiles
echo   spawns         Generate biome and generation filtered Cobblemon spawns
echo   music          Build the selected local audio files as a Paxi resource pack
echo   mod-bootstrap  Build the starter-town NeoForge Java mod JAR
echo   mod-menu       Build the radial player menu NeoForge Java mod JAR
echo   pack-smoke     Build a minimal CurseForge import test ZIP
echo   pack           Build the temporary development CurseForge ZIP
echo   pack-release   Validate release readiness; blocked until dependencies are locked
echo   builder-world  Build the standalone CurseForge structure authoring pack and world
echo   builder-import Import exported NBT from a Structure Builder save into content/structures
exit /b 1
