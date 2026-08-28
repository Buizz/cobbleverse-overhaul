param(
    [string]$WorkspaceRoot = $PSScriptRoot
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$namespace = 'cobbleventure_theme_blocks'
$referenceRoot = Join-Path $WorkspaceRoot 'reference-images/10_glow_windows'
$modelRoot = Join-Path $WorkspaceRoot 'assets/cobbleventure_theme_blocks/models/block/workshop'
$textureRoot = Join-Path $WorkspaceRoot 'assets/cobbleventure_theme_blocks/textures/block/windows'

New-Item -ItemType Directory -Force -Path $textureRoot | Out-Null

function Convert-HexColor([string]$hex) {
    return [System.Drawing.ColorTranslator]::FromHtml($hex)
}

function New-WindowAtlas {
    param(
        [string]$SourcePath,
        [string]$OutputPath,
        [int]$CropX,
        [int]$CropY,
        [int]$CropWidth,
        [int]$CropHeight,
        [string]$FrameBase,
        [string]$FrameLight,
        [string]$FrameDark
    )

    $source = [System.Drawing.Bitmap]::new($SourcePath)
    $atlas = [System.Drawing.Bitmap]::new(32, 16, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    try {
        $base = Convert-HexColor $FrameBase
        $light = Convert-HexColor $FrameLight
        $dark = Convert-HexColor $FrameDark

        for ($y = 0; $y -lt 16; $y++) {
            for ($x = 0; $x -lt 16; $x++) {
                $color = $base
                if ($x -eq 0 -or $y -eq 0) { $color = $light }
                if ($x -eq 15 -or $y -eq 15) { $color = $dark }
                $atlas.SetPixel($x, $y, $color)
            }
        }

        # 오른쪽 16x16 영역에는 커튼을 제외한 원본 창 영역만 최근접 픽셀로 옮긴다.
        for ($y = 0; $y -lt 16; $y++) {
            $sourceY = $CropY + [Math]::Min($CropHeight - 1, [Math]::Floor($y * $CropHeight / 16))
            for ($x = 0; $x -lt 16; $x++) {
                $sourceX = $CropX + [Math]::Min($CropWidth - 1, [Math]::Floor($x * $CropWidth / 16))
                $atlas.SetPixel(16 + $x, $y, $source.GetPixel($sourceX, $sourceY))
            }
        }

        $atlas.Save($OutputPath, [System.Drawing.Imaging.ImageFormat]::Png)
    }
    finally {
        $atlas.Dispose()
        $source.Dispose()
    }
}

function New-FaceSet {
    param([double[]]$Uv)

    $faces = [ordered]@{}
    foreach ($side in @('north', 'east', 'south', 'west', 'up', 'down')) {
        $faces[$side] = [ordered]@{ uv = $Uv; texture = 0 }
    }
    return $faces
}

function New-WindowBbModel {
    param(
        [string]$Name,
        [string]$OutputDirectory,
        [string]$TextureName,
        [double[]]$BodyFrom,
        [double[]]$BodyTo,
        [double[]]$PanelFrom,
        [double[]]$PanelTo
    )

    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    $texturePath = Join-Path $textureRoot $TextureName
    $textureBytes = [System.IO.File]::ReadAllBytes($texturePath)
    $textureSource = 'data:image/png;base64,' + [Convert]::ToBase64String($textureBytes)
    $bodyUuid = [guid]::NewGuid().ToString()
    $panelUuid = [guid]::NewGuid().ToString()

    $model = [ordered]@{
        meta = [ordered]@{
            format_version = '5.0'
            model_format = 'java_block'
            box_uv = $false
        }
        name = $Name
        parent = 'minecraft:block/block'
        java_block_version = '1.21.11'
        ambientocclusion = $true
        front_gui_light = $false
        visible_box = @(1, 1, 0)
        resolution = [ordered]@{ width = 32; height = 16 }
        elements = @(
            [ordered]@{
                name = 'window_body'
                box_uv = $false
                render_order = 'default'
                rescale = $false
                locked = $false
                shade = $true
                light_emission = 0
                export = $true
                from = $BodyFrom
                to = $BodyTo
                autouv = 0
                color = 0
                origin = @(8, 8, 8)
                faces = New-FaceSet @(0, 0, 16, 16)
                type = 'cube'
                uuid = $bodyUuid
            },
            [ordered]@{
                name = 'luminous_panel'
                box_uv = $false
                render_order = 'default'
                rescale = $false
                locked = $false
                shade = $false
                light_emission = 15
                export = $true
                from = $PanelFrom
                to = $PanelTo
                autouv = 0
                color = 3
                origin = @(8, 8, 0)
                faces = New-FaceSet @(16, 0, 32, 16)
                type = 'cube'
                uuid = $panelUuid
            }
        )
        groups = @()
        outliner = @($bodyUuid, $panelUuid)
        textures = @(
            [ordered]@{
                name = $TextureName
                relative_path = "../../../../textures/block/windows/$TextureName"
                folder = 'block/windows'
                namespace = $namespace
                id = '0'
                width = 32
                height = 16
                uv_width = 32
                uv_height = 16
                particle = $true
                file_format = 'png'
                render_mode = 'default'
                render_sides = 'auto'
                wrap_mode = 'limited'
                pbr_channel = 'color'
                visible = $true
                internal = $true
                saved = $true
                uuid = [guid]::NewGuid().ToString()
                source = $textureSource
            }
        )
    }

    $outputPath = Join-Path $OutputDirectory "$Name.bbmodel"
    $model | ConvertTo-Json -Depth 30 -Compress | Set-Content -LiteralPath $outputPath -Encoding utf8NoBOM
}

$windows = @(
    [ordered]@{
        Name = 'sky_view_glow_window'
        Folder = '10_sky_view_glow_window'
        Source = 'sky_view_window_original.png'
        Texture = 'sky_view_glow_window_texture.png'
        Crop = @(1, 1, 12, 12)
        Frame = @('#8d8d8d', '#d9d9d9', '#595959')
        BodyFrom = @(0, 0, 0)
        BodyTo = @(16, 16, 16)
        PanelFrom = @(1.5, 1.5, -0.25)
        PanelTo = @(14.5, 14.5, 0)
    },
    [ordered]@{
        Name = 'bright_double_glow_window'
        Folder = '11_bright_double_glow_window'
        Source = 'bright_double_window_original.png'
        Texture = 'bright_double_glow_window_texture.png'
        # 원본 좌우의 노란 커튼을 제외하고 중앙 유리/창틀 부분만 사용한다.
        Crop = @(5, 0, 14, 13)
        Frame = @('#b4b4a4', '#ffffff', '#7b7b7b')
        BodyFrom = @(-4, 1, 0)
        BodyTo = @(20, 15, 16)
        PanelFrom = @(-2, 2, -0.25)
        PanelTo = @(18, 14, 0)
    },
    [ordered]@{
        Name = 'blue_panel_glow_window'
        Folder = '12_blue_panel_glow_window'
        Source = 'blue_panel_window_original.png'
        Texture = 'blue_panel_glow_window_texture.png'
        Crop = @(1, 1, 12, 10)
        Frame = @('#b4b4a4', '#ded5e6', '#7b7b7b')
        BodyFrom = @(0, 1, 0)
        BodyTo = @(16, 15, 16)
        PanelFrom = @(1.5, 2, -0.25)
        PanelTo = @(14.5, 14, 0)
    }
)

foreach ($window in $windows) {
    $texturePath = Join-Path $textureRoot $window.Texture
    New-WindowAtlas `
        -SourcePath (Join-Path $referenceRoot $window.Source) `
        -OutputPath $texturePath `
        -CropX $window.Crop[0] `
        -CropY $window.Crop[1] `
        -CropWidth $window.Crop[2] `
        -CropHeight $window.Crop[3] `
        -FrameBase $window.Frame[0] `
        -FrameLight $window.Frame[1] `
        -FrameDark $window.Frame[2]

    New-WindowBbModel `
        -Name $window.Name `
        -OutputDirectory (Join-Path $modelRoot $window.Folder) `
        -TextureName $window.Texture `
        -BodyFrom $window.BodyFrom `
        -BodyTo $window.BodyTo `
        -PanelFrom $window.PanelFrom `
        -PanelTo $window.PanelTo
}

Write-Host 'Created three curtain-free glow-window Blockbench drafts.'
