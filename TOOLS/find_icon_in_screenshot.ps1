#Requires -Version 5.1
<#
.SYNOPSIS
    Finds the launcher icon of a package in a device screenshot.

.DESCRIPTION
    End-to-end check that the icon actually reaches the screen: scans a
    `adb exec-out screencap -p` dump for pixels matching the icon's navy plate and
    reports the bounding box of each run found.

    Why not uiautomator: the launcher exposes icons as ImageViews with no text and
    no content-desc, so `uiautomator dump` cannot locate them by name. Matching the
    brand colour in the framebuffer is the reliable way to prove the artwork is
    being drawn by the launcher and not just packaged in the APK.

.PARAMETER Screenshot
    PNG saved from `adb exec-out screencap -p`.

.PARAMETER Color
    Icon plate colour to look for, as #RRGGBB. Defaults to the brand navy.

.PARAMETER Tolerance
    Per-channel tolerance when matching the colour. The launcher may apply its own
    scaling, so an exact match is too strict.

.EXAMPLE
    adb exec-out screencap -p > shot.png
    pwsh -File TOOLS/find_icon_in_screenshot.ps1 -Screenshot shot.png
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $Screenshot,

    [string] $Color = '#0D47A1',

    [int] $Tolerance = 16
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

if (-not (Test-Path $Screenshot)) { throw "Not found: $Screenshot" }

$target = [System.Drawing.ColorTranslator]::FromHtml($Color)

# Load into a 32bpp ARGB bitmap we own, then read the raw buffer once.
# Bitmap.GetPixel() in PowerShell is unusably slow here (a 1220x2712 screenshot is
# 3.3M calls and can exhaust memory), so we copy the pixels into a byte[] and index
# it arithmetically instead.
$src = [System.Drawing.Image]::FromFile((Resolve-Path $Screenshot).Path)
$w = $src.Width
$h = $src.Height
$bmp = New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.DrawImage($src, 0, 0, $w, $h)
$g.Dispose()
$src.Dispose()

try {
    Write-Host ("scanning {0} ({1}x{2}) for {3} +/-{4}" -f (Split-Path $Screenshot -Leaf), $w, $h, $Color, $Tolerance) -ForegroundColor Cyan

    $rect = New-Object System.Drawing.Rectangle(0, 0, $w, $h)
    $data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $stride = $data.Stride
    $bytes = New-Object byte[] ($stride * $h)
    [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
    $bmp.UnlockBits($data)

    # BGRA byte order for Format32bppArgb.
    $tr = [int]$target.R; $tg = [int]$target.G; $tb = [int]$target.B

    # Coarse grid scan: a 48dp icon is >=60px on any modern panel, so sampling
    # every 3px will not step over one.
    $step = 3
    $hits = New-Object System.Collections.ArrayList
    for ($y = 0; $y -lt $h; $y += $step) {
        $rowBase = $y * $stride
        for ($x = 0; $x -lt $w; $x += $step) {
            $i = $rowBase + $x * 4
            if ([math]::Abs([int]$bytes[$i] - $tb) -le $Tolerance -and
                [math]::Abs([int]$bytes[$i + 1] - $tg) -le $Tolerance -and
                [math]::Abs([int]$bytes[$i + 2] - $tr) -le $Tolerance) {
                [void]$hits.Add([pscustomobject]@{ X = $x; Y = $y })
            }
        }
    }

    if ($hits.Count -eq 0) {
        Write-Host 'No matching pixels - the icon is not visible on this screen.' -ForegroundColor Yellow
        Write-Host 'Open the home screen or app drawer page that contains it and retry.' -ForegroundColor Gray
        return
    }

    # Cluster hits by proximity so several icons of the same colour stay separate.
    $clusters = New-Object System.Collections.ArrayList
    foreach ($h in $hits) {
        $placed = $false
        foreach ($c in $clusters) {
            if ([math]::Abs($h.X - $c.Cx) -lt 160 -and [math]::Abs($h.Y - $c.Cy) -lt 160) {
                $c.Points.Add($h) | Out-Null
                $c.Cx = ($c.Points | Measure-Object X -Average).Average
                $c.Cy = ($c.Points | Measure-Object Y -Average).Average
                $placed = $true
                break
            }
        }
        if (-not $placed) {
            $c = [pscustomobject]@{
                Cx = [double]$h.X; Cy = [double]$h.Y
                Points = New-Object System.Collections.ArrayList
            }
            $c.Points.Add($h) | Out-Null
            [void]$clusters.Add($c)
        }
    }

    Write-Host ''
    Write-Host ("{0} matching pixels in {1} region(s):" -f $hits.Count, $clusters.Count) -ForegroundColor Green
    $i = 0
    foreach ($c in $clusters) {
        $i++
        $minX = ($c.Points | Measure-Object X -Minimum).Minimum
        $maxX = ($c.Points | Measure-Object X -Maximum).Maximum
        $minY = ($c.Points | Measure-Object Y -Minimum).Minimum
        $maxY = ($c.Points | Measure-Object Y -Maximum).Maximum
        Write-Host ("  #{0}  bbox x{1}..{2} y{3}..{4}  ({5}x{6}px, {7} samples)" -f `
            $i, $minX, $maxX, $minY, $maxY, ($maxX - $minX), ($maxY - $minY), $c.Points.Count)
    }
} finally {
    $bmp.Dispose()
}
