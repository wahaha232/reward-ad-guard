#Requires -Version 5.1
<#
.SYNOPSIS
    Density-check helper: prints the generated launcher PNG as ASCII art.

.DESCRIPTION
    Verification aid for TOOLS/make_launcher_icons.ps1. Rendering a vector mark
    to a bitmap can go wrong in ways a build cannot catch - wrong coordinates,
    artwork outside the safe zone, a shape that collapses at 48px - so this prints
    a coarse character map of the pixels so the silhouette can be eyeballed in a
    terminal.

    Legend:  W = white pixel, N = navy pixel, . = other
#>
[CmdletBinding()]
param(
    [string] $Png = (Join-Path $env:TEMP 'iconpreview\mipmap-xxxhdpi\ic_launcher.png'),
    [int] $Steps = 32
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

if (-not (Test-Path $Png)) {
    throw "Not found: $Png. Run TOOLS/make_launcher_icons.ps1 first."
}

$bmp = [System.Drawing.Bitmap]::FromFile((Resolve-Path $Png).Path)
try {
    $w = $bmp.Width
    $h = $bmp.Height
    Write-Host ("{0}  {1}x{1}px  (step = {2}px)" -f (Split-Path $Png -Leaf), $w, [math]::Floor($w / $Steps)) -ForegroundColor Cyan
    Write-Host ''

    $step = $w / [double]$Steps
    for ($r = 0; $r -lt $Steps; $r++) {
        $line = New-Object System.Text.StringBuilder
        for ($c = 0; $c -lt $Steps; $c++) {
            # Sample the centre of each cell.
            $x = [int][math]::Min($w - 1, [math]::Floor(($c + 0.5) * $step))
            $y = [int][math]::Min($h - 1, [math]::Floor(($r + 0.5) * $step))
            $px = $bmp.GetPixel($x, $y)
            if ($px.R -gt 200 -and $px.G -gt 200 -and $px.B -gt 200) {
                [void]$line.Append('#')
            } elseif ($px.B -gt 80 -and $px.R -lt 100) {
                [void]$line.Append('+')
            } else {
                [void]$line.Append('.')
            }
        }
        Write-Host $line.ToString()
    }

    # Sanity checks that matter for a legacy launcher icon.
    Write-Host ''
    $corner = $bmp.GetPixel(0, 0)
    Write-Host ("corner pixel      : R{0} G{1} B{2}  (expect navy plate)" -f $corner.R, $corner.G, $corner.B) -ForegroundColor Gray

    $outside = 0
    for ($i = 0; $i -lt $w; $i += [math]::Max(1, [math]::Floor($w / 32))) {
        foreach ($p in @(@($i, 0), @($i, ($h - 1)), @(0, $i), @(($w - 1), $i))) {
            $px = $bmp.GetPixel($p[0], $p[1])
            if ($px.R -gt 200 -and $px.G -gt 200) { $outside++ }
        }
    }
    if ($outside -eq 0) {
        Write-Host 'safe margin       : OK - no white touches the outer 2% border' -ForegroundColor Green
    } else {
        Write-Host ("safe margin       : WARNING - {0} white border samples; art may be clipped" -f $outside) -ForegroundColor Yellow
    }
} finally {
    $bmp.Dispose()
}
