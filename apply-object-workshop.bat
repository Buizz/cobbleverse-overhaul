@echo off
setlocal
pushd "%~dp0"
call build.bat mod-theme-blocks
set "BUILD_EXIT=%errorlevel%"
if not "%BUILD_EXIT%"=="0" (
    echo.
    echo [ERROR] Object workshop build failed.
    popd
    exit /b %BUILD_EXIT%
)
echo.
echo [OK] Editable BBModels and textures were converted and copied into the code JAR.
echo [OK] The updated JAR was installed into all three pack override folders.
echo [INFO] Restart Minecraft before checking the updated models.
popd
exit /b 0
