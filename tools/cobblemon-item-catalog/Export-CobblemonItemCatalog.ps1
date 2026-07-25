[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Container })]
    [string]$ModsDirectory,

    [string]$OutputDirectory = ""
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem

if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $PSScriptRoot "..\..\trainer-data\catalogs"
}

function Read-ZipJson {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.Compression.ZipArchiveEntry]$Entry
    )

    $stream = $Entry.Open()
    $reader = New-Object System.IO.StreamReader(
        $stream,
        [System.Text.UTF8Encoding]::new($false),
        $true
    )
    try {
        $text = $reader.ReadToEnd()
        return $text | ConvertFrom-Json
    }
    finally {
        $reader.Dispose()
        $stream.Dispose()
    }
}

function Get-JsonProperties {
    param([object]$Value)

    if ($null -eq $Value) {
        return @()
    }
    return @($Value.PSObject.Properties)
}

function Add-Source {
    param(
        [hashtable]$Sources,
        [string]$ItemId,
        [string]$JarName
    )

    if (-not $Sources.ContainsKey($ItemId)) {
        $Sources[$ItemId] = [System.Collections.Generic.HashSet[string]]::new(
            [System.StringComparer]::OrdinalIgnoreCase
        )
    }
    [void]$Sources[$ItemId].Add($JarName)
}

function Resolve-ItemTag {
    param(
        [string]$TagId,
        [hashtable]$Tags,
        [System.Collections.Generic.HashSet[string]]$Resolving
    )

    $normalizedTagId = $TagId.ToLowerInvariant()
    if ($Resolving.Contains($normalizedTagId)) {
        return @()
    }

    [void]$Resolving.Add($normalizedTagId)
    $resolved = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )

    if ($Tags.ContainsKey($normalizedTagId)) {
        foreach ($entry in $Tags[$normalizedTagId]) {
            $value = if ($entry -is [string]) {
                $entry
            }
            elseif ($null -ne $entry -and $null -ne $entry.id) {
                [string]$entry.id
            }
            else {
                ""
            }

            $value = $value.Trim().ToLowerInvariant()
            if (-not $value) {
                continue
            }
            if ($value.StartsWith("#")) {
                $nested = $value.Substring(1)
                if (-not $nested.Contains(":")) {
                    $namespace = $normalizedTagId.Split(":", 2)[0]
                    $nested = "${namespace}:${nested}"
                }
                foreach ($item in (Resolve-ItemTag $nested $Tags $Resolving)) {
                    [void]$resolved.Add($item)
                }
            }
            elseif ($value.Contains(":")) {
                [void]$resolved.Add($value)
            }
        }
    }

    [void]$Resolving.Remove($normalizedTagId)
    return @($resolved)
}

function Resolve-RootTag {
    param([string]$TagId, [hashtable]$Tags)
    $resolving = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )
    $result = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )
    foreach ($itemId in @(Resolve-ItemTag $TagId $Tags $resolving)) {
        [void]$result.Add($itemId)
    }
    return $result
}

function Convert-ToCsvCell {
    param([object]$Value)
    $text = if ($null -eq $Value) { "" } else { [string]$Value }
    return '"' + $text.Replace('"', '""') + '"'
}

function Get-ShortErrorMessage {
    param([System.Exception]$Exception)
    $message = [string]$Exception.Message
    if ($message.Length -le 500) {
        return $message
    }
    return $message.Substring(0, 500) + "..."
}

$modsPath = (Resolve-Path -LiteralPath $ModsDirectory).Path
$outputPath = [System.IO.Path]::GetFullPath($OutputDirectory)
$jars = @(
    Get-ChildItem -LiteralPath $modsPath |
        Where-Object { -not $_.PSIsContainer -and $_.Name.EndsWith(".jar", [System.StringComparison]::OrdinalIgnoreCase) } |
        Sort-Object Name
)

if ($jars.Count -eq 0) {
    throw "활성화된 .jar 파일을 찾지 못했습니다: $modsPath"
}

$languages = @{}
$tags = @{}
$itemSources = @{}
$tagSources = @{}
$jarSummaries = [System.Collections.Generic.List[object]]::new()
$warnings = [System.Collections.Generic.List[object]]::new()

foreach ($jar in $jars) {
    $contributedItems = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )
    $contributedTags = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )
    $archive = $null
    try {
        $archive = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
        foreach ($entry in $archive.Entries) {
            $name = $entry.FullName.Replace("\", "/")
            $languageMatch = [regex]::Match(
                $name,
                "^assets/([^/]+)/lang/(en_us|ko_kr)\.json$",
                [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
            )
            if ($languageMatch.Success) {
                $namespace = $languageMatch.Groups[1].Value.ToLowerInvariant()
                $locale = $languageMatch.Groups[2].Value.ToLowerInvariant()
                try {
                    $data = Read-ZipJson $entry
                    foreach ($property in (Get-JsonProperties $data)) {
                        $itemMatch = [regex]::Match(
                            $property.Name,
                            "^item\.([^.]+)\.([^.]+)$",
                            [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
                        )
                        if (-not $itemMatch.Success) {
                            continue
                        }
                        $itemNamespace = $itemMatch.Groups[1].Value.ToLowerInvariant()
                        $itemPath = $itemMatch.Groups[2].Value.ToLowerInvariant()
                        $itemId = "${itemNamespace}:${itemPath}"
                        if (-not $languages.ContainsKey($itemId)) {
                            $languages[$itemId] = @{}
                        }
                        if (-not $languages[$itemId].ContainsKey($locale)) {
                            $languages[$itemId][$locale] = [string]$property.Value
                        }
                        [void]$contributedItems.Add($itemId)
                        Add-Source $itemSources $itemId $jar.Name
                    }
                }
                catch {
                    $warnings.Add([ordered]@{
                        jar = $jar.Name
                        resource = $name
                        message = Get-ShortErrorMessage $_.Exception
                    })
                }
                continue
            }

            $tagMatch = [regex]::Match(
                $name,
                "^data/([^/]+)/tags/item/(.+)\.json$",
                [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
            )
            if (-not $tagMatch.Success) {
                continue
            }

            $namespace = $tagMatch.Groups[1].Value.ToLowerInvariant()
            $tagPath = $tagMatch.Groups[2].Value.ToLowerInvariant()
            $tagId = "${namespace}:${tagPath}"
            try {
                $data = Read-ZipJson $entry
                if ($data.replace -eq $true -or -not $tags.ContainsKey($tagId)) {
                    $tags[$tagId] = [System.Collections.Generic.List[object]]::new()
                }
                if ($null -ne $data.values) {
                    foreach ($value in @($data.values)) {
                        $tags[$tagId].Add($value)
                        $rawValue = if ($value -is [string]) { $value } else { [string]$value.id }
                        if ($rawValue -and -not $rawValue.StartsWith("#") -and $rawValue.Contains(":")) {
                            $itemId = $rawValue.ToLowerInvariant()
                            [void]$contributedItems.Add($itemId)
                            Add-Source $itemSources $itemId $jar.Name
                        }
                    }
                }
                [void]$contributedTags.Add($tagId)
                if (-not $tagSources.ContainsKey($tagId)) {
                    $tagSources[$tagId] = [System.Collections.Generic.HashSet[string]]::new(
                        [System.StringComparer]::OrdinalIgnoreCase
                    )
                }
                [void]$tagSources[$tagId].Add($jar.Name)
            }
            catch {
                $warnings.Add([ordered]@{
                    jar = $jar.Name
                    resource = $name
                    message = Get-ShortErrorMessage $_.Exception
                })
            }
        }
    }
    catch {
        $warnings.Add([ordered]@{
            jar = $jar.Name
            resource = $null
            message = Get-ShortErrorMessage $_.Exception
        })
    }
    finally {
        if ($null -ne $archive) {
            $archive.Dispose()
        }
    }

    if ($contributedItems.Count -gt 0 -or $contributedTags.Count -gt 0) {
        $jarSummaries.Add([ordered]@{
            file = $jar.Name
            size = $jar.Length
            lastWriteTimeUtc = $jar.LastWriteTimeUtc.ToString("o")
            itemCount = $contributedItems.Count
            itemTagCount = $contributedTags.Count
        })
    }
}

$heldItems = Resolve-RootTag "cobblemon:held/is_held_item" $tags
$nonBattleBerries = Resolve-RootTag "cobblemon:berries/non_battle" $tags
$allBerries = Resolve-RootTag "cobblemon:berries" $tags
$typeGems = Resolve-RootTag "cobblemon:type_gems" $tags
$megaStones = Resolve-RootTag "mega_showdown:mega_stone" $tags
$zCrystals = Resolve-RootTag "mega_showdown:z_crystal" $tags

$battleBerries = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::OrdinalIgnoreCase
)
foreach ($itemId in $allBerries) {
    [void]$battleBerries.Add($itemId)
}
foreach ($itemId in $nonBattleBerries) {
    [void]$battleBerries.Remove($itemId)
}

$allItemIds = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::OrdinalIgnoreCase
)
foreach ($itemId in $languages.Keys) { [void]$allItemIds.Add($itemId) }
foreach ($itemId in $itemSources.Keys) { [void]$allItemIds.Add($itemId) }
foreach ($itemId in $heldItems) { [void]$allItemIds.Add($itemId) }
foreach ($itemId in $battleBerries) { [void]$allItemIds.Add($itemId) }
foreach ($itemId in $typeGems) { [void]$allItemIds.Add($itemId) }
foreach ($itemId in $megaStones) { [void]$allItemIds.Add($itemId) }
foreach ($itemId in $zCrystals) { [void]$allItemIds.Add($itemId) }

$itemTagMembership = @{}
foreach ($tagId in $tags.Keys) {
    foreach ($itemId in (Resolve-RootTag $tagId $tags)) {
        if (-not $itemTagMembership.ContainsKey($itemId)) {
            $itemTagMembership[$itemId] = [System.Collections.Generic.HashSet[string]]::new(
                [System.StringComparer]::OrdinalIgnoreCase
            )
        }
        [void]$itemTagMembership[$itemId].Add($tagId)
    }
}

$items = [System.Collections.Generic.List[object]]::new()
foreach ($itemId in @($allItemIds | Sort-Object)) {
    $parts = $itemId.Split(":", 2)
    $namespace = $parts[0]
    $itemPath = $parts[1]
    $battleCategory = if ($megaStones.Contains($itemId)) {
        "mega"
    }
    elseif ($zCrystals.Contains($itemId)) {
        "z"
    }
    elseif ($battleBerries.Contains($itemId)) {
        "berry"
    }
    elseif ($typeGems.Contains($itemId)) {
        "gem"
    }
    elseif ($heldItems.Contains($itemId)) {
        "held"
    }
    else {
        "unverified"
    }

    $englishName = if ($languages.ContainsKey($itemId) -and $languages[$itemId].ContainsKey("en_us")) {
        $languages[$itemId]["en_us"]
    } else { "" }
    $koreanName = if ($languages.ContainsKey($itemId) -and $languages[$itemId].ContainsKey("ko_kr")) {
        $languages[$itemId]["ko_kr"]
    } else { "" }
    [string[]]$sources = if ($itemSources.ContainsKey($itemId)) {
        @($itemSources[$itemId] | Sort-Object)
    } else { @() }
    [string[]]$memberTags = if ($itemTagMembership.ContainsKey($itemId)) {
        @($itemTagMembership[$itemId] | Sort-Object)
    } else { @() }

    $items.Add([ordered]@{
        id = $itemId
        namespace = $namespace
        path = $itemPath
        englishName = $englishName
        koreanName = $koreanName
        battleCategory = $battleCategory
        battleUsable = $battleCategory -ne "unverified"
        tags = $memberTags
        sourceJars = $sources
    })
}

$catalog = [ordered]@{
    schemaVersion = 1
    generatedAt = [DateTime]::UtcNow.ToString("o")
    source = [ordered]@{
        kind = "minecraft-mod-jars"
        activeJarCount = $jars.Count
        contributingJars = $jarSummaries
    }
    roots = [ordered]@{
        heldItem = "cobblemon:held/is_held_item"
        berries = "cobblemon:berries"
        nonBattleBerries = "cobblemon:berries/non_battle"
        typeGems = "cobblemon:type_gems"
        megaStones = "mega_showdown:mega_stone"
        zCrystals = "mega_showdown:z_crystal"
    }
    itemCount = $items.Count
    battleItemCount = @($items | Where-Object { $_.battleUsable }).Count
    items = $items
    warnings = $warnings
}

[System.IO.Directory]::CreateDirectory($outputPath) | Out-Null
$jsonPath = Join-Path $outputPath "cobblemon-items.json"
$csvPath = Join-Path $outputPath "cobblemon-items.csv"
$manifestPath = Join-Path $outputPath "cobblemon-item-sources.json"

$json = $catalog | ConvertTo-Json -Depth 20
[System.IO.File]::WriteAllText(
    $jsonPath,
    $json + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false)
)

$csvLines = [System.Collections.Generic.List[string]]::new()
$csvLines.Add(
    '"id","namespace","path","english_name","korean_name","battle_category","battle_usable","tags","source_jars"'
)
foreach ($item in $items) {
    $cells = @(
        $item.id,
        $item.namespace,
        $item.path,
        $item.englishName,
        $item.koreanName,
        $item.battleCategory,
        ([string]$item.battleUsable).ToLowerInvariant(),
        ($item.tags -join ";"),
        ($item.sourceJars -join ";")
    ) | ForEach-Object { Convert-ToCsvCell $_ }
    $csvLines.Add($cells -join ",")
}
[System.IO.File]::WriteAllLines(
    $csvPath,
    $csvLines,
    [System.Text.UTF8Encoding]::new($true)
)

$manifest = [ordered]@{
    schemaVersion = 1
    generatedAt = $catalog.generatedAt
    activeJarCount = $jars.Count
    contributingJars = $jarSummaries
    tagCount = $tags.Count
    warningCount = $warnings.Count
}
[System.IO.File]::WriteAllText(
    $manifestPath,
    ($manifest | ConvertTo-Json -Depth 10) + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false)
)

Write-Host "Cobblemon item catalog generated."
Write-Host "  Active JARs: $($jars.Count)"
Write-Host "  Contributing JARs: $($jarSummaries.Count)"
Write-Host "  Items: $($items.Count)"
Write-Host "  Battle items: $($catalog.battleItemCount)"
Write-Host "  Warnings: $($warnings.Count)"
Write-Host "  JSON: $jsonPath"
Write-Host "  CSV:  $csvPath"
