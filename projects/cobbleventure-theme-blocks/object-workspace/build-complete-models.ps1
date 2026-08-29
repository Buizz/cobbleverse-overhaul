$ErrorActionPreference = 'Stop'

$workspaceRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$modelRoot = Join-Path $workspaceRoot 'assets/cobbleventure_theme_blocks/models/block/workshop'
$namespacePrefix = 'cobbleventure_theme_blocks:block/workshop/'

function Read-Model([string]$relativePath) {
    $path = Join-Path $modelRoot ($relativePath + '.json')
    return Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
}

function Resolve-Model([string]$relativePath) {
    $model = Read-Model $relativePath
    $textures = [ordered]@{}
    $elements = @()

    if ($model.parent -and $model.parent.StartsWith($namespacePrefix)) {
        $parent = Resolve-Model $model.parent.Substring($namespacePrefix.Length)
        foreach ($entry in $parent.textures.GetEnumerator()) {
            $textures[$entry.Key] = $entry.Value
        }
        $elements = @($parent.elements)
    }

    if ($model.textures) {
        foreach ($entry in $model.textures.PSObject.Properties) {
            $textures[$entry.Name] = $entry.Value
        }
    }
    if ($model.elements) {
        $elements = @($model.elements)
    }

    return [pscustomobject]@{
        textures = $textures
        elements = $elements
    }
}

function Rotate-Point([double[]]$point, [int]$rotation) {
    $x = $point[0]
    $y = $point[1]
    $z = $point[2]
    switch ($rotation % 360) {
        0   { return @($x, $y, $z) }
        90  { return @((16 - $z), $y, $x) }
        180 { return @((16 - $x), $y, (16 - $z)) }
        270 { return @($z, $y, (16 - $x)) }
        default { throw "지원하지 않는 회전각입니다: $rotation" }
    }
}

function Rotate-FaceName([string]$faceName, [int]$rotation) {
    if ($faceName -eq 'up' -or $faceName -eq 'down' -or $rotation -eq 0) {
        return $faceName
    }
    $directions = @('north', 'east', 'south', 'west')
    $index = [Array]::IndexOf($directions, $faceName)
    if ($index -lt 0) {
        return $faceName
    }
    return $directions[($index + ($rotation / 90)) % 4]
}

function Add-Component(
    [System.Collections.Generic.List[object]]$targetElements,
    [System.Collections.Specialized.OrderedDictionary]$targetTextures,
    [string]$modelPath,
    [double[]]$offset,
    [int]$rotation,
    [string]$componentName
) {
    $resolved = Resolve-Model $modelPath
    $textureMap = @{}

    foreach ($entry in $resolved.textures.GetEnumerator()) {
        if ($entry.Key -eq 'particle') {
            if (-not $targetTextures.Contains('particle')) {
                $targetTextures['particle'] = $entry.Value
            }
            continue
        }
        $newKey = "${componentName}_$($entry.Key)"
        $targetTextures[$newKey] = $entry.Value
        $textureMap[$entry.Key] = $newKey
    }

    $elementIndex = 0
    foreach ($sourceElement in $resolved.elements) {
        $element = $sourceElement | ConvertTo-Json -Depth 100 | ConvertFrom-Json
        $from = Rotate-Point ([double[]]$element.from) $rotation
        $to = Rotate-Point ([double[]]$element.to) $rotation
        $element.from = @(
            [double]([Math]::Min([double]$from[0], [double]$to[0]) + [double]$offset[0]),
            [double]([Math]::Min([double]$from[1], [double]$to[1]) + [double]$offset[1]),
            [double]([Math]::Min([double]$from[2], [double]$to[2]) + [double]$offset[2])
        )
        $element.to = @(
            [double]([Math]::Max([double]$from[0], [double]$to[0]) + [double]$offset[0]),
            [double]([Math]::Max([double]$from[1], [double]$to[1]) + [double]$offset[1]),
            [double]([Math]::Max([double]$from[2], [double]$to[2]) + [double]$offset[2])
        )
        $element.name = "${componentName}_$($element.name ?? "element_$elementIndex")"

        if ($element.faces) {
            $rotatedFaces = [ordered]@{}
            foreach ($face in $element.faces.PSObject.Properties) {
                $faceValue = $face.Value
                if ($faceValue.texture -and $faceValue.texture.StartsWith('#')) {
                    $oldKey = $faceValue.texture.Substring(1)
                    if ($textureMap.ContainsKey($oldKey)) {
                        $faceValue.texture = '#' + $textureMap[$oldKey]
                    }
                }
                $rotatedFaces[(Rotate-FaceName $face.Name $rotation)] = $faceValue
            }
            $element.faces = [pscustomobject]$rotatedFaces
        }

        $targetElements.Add($element)
        $elementIndex++
    }
}

function Write-CompleteModel([string]$folder, [string]$fileName, [object[]]$components) {
    $textures = [ordered]@{}
    $elements = [System.Collections.Generic.List[object]]::new()
    foreach ($component in $components) {
        Add-Component $elements $textures $component.model $component.offset $component.rotation $component.name
    }

    $output = [ordered]@{
        parent = 'minecraft:block/block'
        ambientocclusion = $true
        textures = $textures
        elements = $elements
    }
    $path = Join-Path $modelRoot "$folder/$fileName.json"
    $output | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $path -Encoding utf8
    Write-Host "생성: $path ($($elements.Count) elements)"
}

function Component([string]$model, [double]$x, [double]$y, [double]$z, [int]$rotation, [string]$name) {
    return [pscustomobject]@{ model=$model; offset=@($x,$y,$z); rotation=$rotation; name=$name }
}

Write-CompleteModel '01_pokemon_tower_grave' 'pokemon_tower_grave_complete' @(
    (Component '01_pokemon_tower_grave/pokemon_tower_grave' 0 0 0 0 'grave')
)

Write-CompleteModel '02_double_display_case' 'double_display_case_complete' @(
    (Component '02_double_display_case/double_display_case_lower_left' 0 0 0 0 'lower_left'),
    (Component '02_double_display_case/double_display_case_lower_right' 16 0 0 0 'lower_right'),
    (Component '02_double_display_case/double_display_case_upper_left' 0 16 0 0 'upper_left'),
    (Component '02_double_display_case/double_display_case_upper_right' 16 16 0 0 'upper_right')
)

Write-CompleteModel '03_double_glass_display_counter' 'double_glass_display_counter_complete' @(
    (Component '03_double_glass_display_counter/double_glass_display_counter_left' 0 0 0 0 'left'),
    (Component '03_double_glass_display_counter/double_glass_display_counter_right_edge' 0 0 0 0 'left_outer_edge'),
    (Component '03_double_glass_display_counter/double_glass_display_counter_right' 16 0 0 0 'right'),
    (Component '03_double_glass_display_counter/double_glass_display_counter_left_edge' 16 0 0 0 'right_outer_edge')
)

Write-CompleteModel '04_rocket_base_machine_1' 'rocket_base_machine_1_complete' @(
    (Component '04_rocket_base_machine_1/rocket_base_machine_1_lower' 0 0 0 0 'lower'),
    (Component '04_rocket_base_machine_1/rocket_base_machine_1_upper' 0 16 0 0 'upper')
)

Write-CompleteModel '05_rocket_base_machine_2' 'rocket_base_machine_2_complete' @(
    (Component '05_rocket_base_machine_2/rocket_base_machine_2_lower' 0 0 0 0 'lower'),
    (Component '05_rocket_base_machine_2/rocket_base_machine_2_upper' 0 16 0 0 'upper')
)

Write-CompleteModel '06_rocket_base_machine_3' 'rocket_base_machine_3_complete' @(
    (Component '06_rocket_base_machine_3/rocket_base_machine_3_left_lower' 0 0 0 0 'left_lower'),
    (Component '06_rocket_base_machine_3/rocket_base_machine_3_right_lower' -16 0 0 0 'right_lower'),
    (Component '06_rocket_base_machine_3/rocket_base_machine_3_left_middle' 0 16 0 0 'left_middle'),
    (Component '06_rocket_base_machine_3/rocket_base_machine_3_right_middle' -16 16 0 0 'right_middle'),
    (Component '06_rocket_base_machine_3/rocket_base_machine_3_left_upper' 0 32 0 0 'left_upper'),
    (Component '06_rocket_base_machine_3/rocket_base_machine_3_right_upper' -16 32 0 0 'right_upper')
)

Write-CompleteModel '08_professor_lab_connecting_bookshelf' 'professor_lab_connecting_bookshelf_complete' @(
    (Component '08_professor_lab_connecting_bookshelf/professor_lab_connecting_bookshelf_core' 0 0 0 0 'core'),
    (Component '08_professor_lab_connecting_bookshelf/professor_lab_connecting_bookshelf_left_end' 0 0 0 0 'left_end'),
    (Component '08_professor_lab_connecting_bookshelf/professor_lab_connecting_bookshelf_right_end' 0 0 0 0 'right_end')
)
