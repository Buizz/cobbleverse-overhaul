$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Drawing

$workspaceRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$modelRoot = Join-Path $workspaceRoot 'assets/cobbleventure_theme_blocks/models/block/workshop'
$textureRoot = Join-Path $workspaceRoot 'assets/cobbleventure_theme_blocks/textures/block'
$namespace = 'cobbleventure_theme_blocks'
$culture = [System.Globalization.CultureInfo]::InvariantCulture

$machines = @(
    @{ folder='04_rocket_base_machine_1'; source='rocket_base_machine_1_complete'; output='rocket_base_machine_1'; atlas='rocket_base_machine_1_texture' },
    @{ folder='05_rocket_base_machine_2'; source='rocket_base_machine_2_complete'; output='rocket_base_machine_2'; atlas='rocket_base_machine_2_texture' },
    @{ folder='06_rocket_base_machine_3'; source='rocket_base_machine_3_complete'; output='rocket_base_machine_3'; atlas='rocket_base_machine_3_texture' }
)

function Number([double]$value) {
    return $value.ToString('0.######', $culture)
}

function Safe-Name([string]$name) {
    return ($name -replace '[^A-Za-z0-9_\-]', '_')
}

function Texture-File([string]$resourceLocation) {
    $prefix = "${namespace}:block/"
    if (-not $resourceLocation.StartsWith($prefix)) {
        throw "지원하지 않는 텍스처 경로입니다: $resourceLocation"
    }
    return Join-Path $textureRoot ($resourceLocation.Substring($prefix.Length) + '.png')
}

function Face-Geometry([object]$element, [string]$faceName) {
    $x0=[double]$element.from[0]/16; $y0=[double]$element.from[1]/16; $z0=[double]$element.from[2]/16
    $x1=[double]$element.to[0]/16;   $y1=[double]$element.to[1]/16;   $z1=[double]$element.to[2]/16
    switch ($faceName) {
        'down'  { return @{ normal=@(0,-1,0); points=@(@($x0,$y0,$z0),@($x1,$y0,$z0),@($x1,$y0,$z1),@($x0,$y0,$z1)) } }
        'up'    { return @{ normal=@(0,1,0);  points=@(@($x0,$y1,$z0),@($x0,$y1,$z1),@($x1,$y1,$z1),@($x1,$y1,$z0)) } }
        'north' { return @{ normal=@(0,0,-1); points=@(@($x0,$y0,$z0),@($x0,$y1,$z0),@($x1,$y1,$z0),@($x1,$y0,$z0)) } }
        'south' { return @{ normal=@(0,0,1);  points=@(@($x0,$y0,$z1),@($x1,$y0,$z1),@($x1,$y1,$z1),@($x0,$y1,$z1)) } }
        'west'  { return @{ normal=@(-1,0,0); points=@(@($x0,$y0,$z0),@($x0,$y0,$z1),@($x0,$y1,$z1),@($x0,$y1,$z0)) } }
        'east'  { return @{ normal=@(1,0,0);  points=@(@($x1,$y0,$z0),@($x1,$y1,$z0),@($x1,$y1,$z1),@($x1,$y0,$z1)) } }
        default { throw "지원하지 않는 면입니다: $faceName" }
    }
}

foreach ($machine in $machines) {
    $folderPath = Join-Path $modelRoot $machine.folder
    $sourcePath = Join-Path $folderPath ($machine.source + '.json')
    $model = Get-Content -LiteralPath $sourcePath -Raw | ConvertFrom-Json

    $textureResources = [System.Collections.Generic.List[string]]::new()
    foreach ($entry in $model.textures.PSObject.Properties) {
        if ($entry.Name -ne 'particle' -and -not $textureResources.Contains([string]$entry.Value)) {
            $textureResources.Add([string]$entry.Value)
        }
    }
    if ($textureResources.Count -eq 0) {
        throw "OBJ로 변환할 텍스처가 없습니다: $sourcePath"
    }

    $columns = [Math]::Min(4, $textureResources.Count)
    $rows = [Math]::Ceiling($textureResources.Count / $columns)
    $atlasWidth = $columns * 16
    $atlasHeight = $rows * 16
    $atlasPath = Join-Path $textureRoot ($machine.atlas + '.png')
    $atlas = [System.Drawing.Bitmap]::new(
        $atlasWidth,
        $atlasHeight,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb
    )
    try {
        $graphics = [System.Drawing.Graphics]::FromImage($atlas)
        try {
            $graphics.Clear([System.Drawing.Color]::Transparent)
            for ($index=0; $index -lt $textureResources.Count; $index++) {
                $sourceTexture = [System.Drawing.Image]::FromFile((Texture-File $textureResources[$index]))
                try {
                    if ($sourceTexture.Width -ne 16 -or $sourceTexture.Height -ne 16) {
                        throw "16x16이 아닌 기계 텍스처입니다: $($textureResources[$index])"
                    }
                    $graphics.DrawImageUnscaled(
                        $sourceTexture,
                        ($index % $columns) * 16,
                        [Math]::Floor($index / $columns) * 16
                    )
                } finally {
                    $sourceTexture.Dispose()
                }
            }
        } finally {
            $graphics.Dispose()
        }
        $atlas.Save($atlasPath, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $atlas.Dispose()
    }

    $textureSlots = @{}
    for ($index=0; $index -lt $textureResources.Count; $index++) {
        $textureSlots[$textureResources[$index]] = @{
            u0=(($index % $columns) * 16) / $atlasWidth
            v0=([Math]::Floor($index / $columns) * 16) / $atlasHeight
            u1=((($index % $columns) + 1) * 16) / $atlasWidth
            v1=(([Math]::Floor($index / $columns) + 1) * 16) / $atlasHeight
        }
    }

    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add('# Generated from the Cobbleventure Blockbench workspace model.')
    $lines.Add("mtllib $($machine.output).mtl")
    $lines.Add('usemtl machine_texture')
    $lines.Add('s off')
    $vertexIndex = 1
    $textureIndex = 1
    $normalIndex = 1

    foreach ($element in $model.elements) {
        $lines.Add('o ' + (Safe-Name ([string]$element.name)))
        foreach ($faceProperty in $element.faces.PSObject.Properties) {
            $face = $faceProperty.Value
            $key = ([string]$face.texture).TrimStart('#')
            $resource = [string]$model.textures.PSObject.Properties[$key].Value
            $slot = $textureSlots[$resource]
            if (-not $slot) {
                throw "아틀라스 슬롯을 찾을 수 없습니다: $resource"
            }
            $geometry = Face-Geometry $element $faceProperty.Name
            foreach ($point in $geometry.points) {
                $lines.Add("v $(Number $point[0]) $(Number $point[1]) $(Number $point[2])")
            }
            # Wavefront OBJ uses a bottom-left texture origin. Keep the editable OBJ
            # conventional for Blockbench and let NeoForge flip V while loading.
            $lines.Add("vt $(Number $slot.u0) $(Number (1.0 - $slot.v0))")
            $lines.Add("vt $(Number $slot.u0) $(Number (1.0 - $slot.v1))")
            $lines.Add("vt $(Number $slot.u1) $(Number (1.0 - $slot.v1))")
            $lines.Add("vt $(Number $slot.u1) $(Number (1.0 - $slot.v0))")
            $lines.Add("vn $(Number $geometry.normal[0]) $(Number $geometry.normal[1]) $(Number $geometry.normal[2])")
            $v=$vertexIndex; $t=$textureIndex; $n=$normalIndex
            $lines.Add("f $v/$t/$n $($v+1)/$($t+1)/$n $($v+2)/$($t+2)/$n")
            $lines.Add("f $v/$t/$n $($v+2)/$($t+2)/$n $($v+3)/$($t+3)/$n")
            $vertexIndex += 4
            $textureIndex += 4
            $normalIndex++
        }
    }
    Set-Content -LiteralPath (Join-Path $folderPath ($machine.output + '.obj')) -Value $lines -Encoding utf8

    @(
        '# Blockbench OBJ material',
        'newmtl machine_texture',
        'Ka 1.0 1.0 1.0',
        'Kd 1.0 1.0 1.0',
        'd 1.0',
        "map_Kd ../../../../textures/block/$($machine.atlas).png"
    ) | Set-Content -LiteralPath (Join-Path $folderPath ($machine.output + '.mtl')) -Encoding utf8

    @(
        '# NeoForge OBJ material',
        'newmtl machine_texture',
        'Ka 1.0 1.0 1.0',
        'Kd 1.0 1.0 1.0',
        'd 1.0',
        'map_Kd #texture0'
    ) | Set-Content -LiteralPath (Join-Path $folderPath ($machine.output + '.neoforge.mtl')) -Encoding utf8

    $wrapper = [ordered]@{
        loader = 'neoforge:obj'
        model = "${namespace}:models/block/workshop/$($machine.folder)/$($machine.output).obj"
        mtl_override = "${namespace}:models/block/workshop/$($machine.folder)/$($machine.output).neoforge.mtl"
        textures = [ordered]@{
            texture0 = "${namespace}:block/$($machine.atlas)"
            particle = "${namespace}:block/$($machine.atlas)"
        }
        automatic_culling = $false
        shade_quads = $true
        flip_v = $true
        emissive_ambient = $false
    }
    $wrapper | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $folderPath ($machine.output + '.json')) -Encoding utf8

    Write-Host "OBJ 생성: $($machine.folder)/$($machine.output) ($($model.elements.Count) elements, $($textureResources.Count) textures)"
}
