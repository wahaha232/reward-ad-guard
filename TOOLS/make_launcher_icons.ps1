#Requires -Version 5.1
<#
.SYNOPSIS
    Rasterises the Reward Ad Guard launcher mark into the legacy mipmap densities.

.DESCRIPTION
    The adaptive icon in res/mipmap-anydpi-v26 only covers API 26+. Devices below
    that (and several third-party launchers, which read the density-specific
    mipmaps directly rather than going through the adaptive-icon path) need real
    bitmaps, otherwise the app installs with a blank or default icon.

    This script is the single source of truth for that fallback. It redraws the
    same geometry as res/drawable/ic_launcher_foreground.xml - shield + redirect
    arrow + blocking barrier - but as a SELF-CONTAINED square: an adaptive icon
    gets its background plate for free from the launcher, a legacy icon does not,
    so here the navy plate is drawn first and the shield is scaled down to sit on
    it with a safe margin.

    Geometry is authored once in a 108x108 design space (the adaptive-icon
    viewport) and scaled linearly, so tweaking a coordinate here or in the vector
    keeps them visually identical. If you change the artwork, re-run this script.

.PARAMETER OutputRoot
    res/ directory to write mipmap-*/ic_launcher.png into.
    Defaults to app/src/main/res relative to the repository root.

.PARAMETER Force
    Overwrite existing files. Without it, an existing PNG is left alone.

.EXAMPLE
    pwsh -File TOOLS/make_launcher_icons.ps1 -Force
#>
[CmdletBinding()]
param(
    [string] $OutputRoot = (Join-Path $PSScriptRoot '..\app\src\main\res'),
    [switch] $Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Drawing

# ---------------------------------------------------------------- design space
# Every number below is in the 108x108 adaptive-icon viewport, matching
# ic_launcher_foreground.xml. ART_SCALE shrinks that art so it does not touch the
# edges of a legacy square icon.
$Canvas = 108.0
$ArtScale = 0.76

# Brand colours - keep in sync with res/values/colors.xml.
$Navy = [System.Drawing.ColorTranslator]::FromHtml('#0D47A1')
$White = [System.Drawing.Color]::White

# Shield outline, same control points as the vector foreground.
$ShieldTop = 21.0
$ShieldShoulder = 30.0
$ShieldBottom = 87.0
$ShieldSpine = 54.0          # vertical centre
$ShieldHalfWidth = 20.0      # 34..74 around the spine
$ShieldFlankBottom = 55.0    # straight flanks end here, curve begins
$ShieldControlY = 71.0
$ShieldControlX = 65.0
$ShieldShoulderUpper = 82.0  # upper bezier control y for the bottom point

# Mipmap densities: name -> icon edge length in px (48dp rendered at each bucket).
$Densities = [ordered]@{
    'mipmap-mdpi'    = 48
    'mipmap-hdpi'    = 72
    'mipmap-xhdpi'   = 96
    'mipmap-xxhdpi'  = 144
    'mipmap-xxxhdpi' = 192
}

function ConvertTo-ScaledPoint {
    <# Maps a design-space coordinate to a device pixel. #>
    param([double] $X, [double] $Y, [double] $Px)

    $scale = ($Px / $Canvas) * $ArtScale
    $offset = ($Px - $Canvas * $scale) / 2.0
    return [System.Drawing.PointF]::new(
        [single]($X * $scale + $offset),
        [single]($Y * $scale + $offset))
}

function New-LauncherBitmap {
    <#
      Draws one square legacy icon at the requested pixel size.

      Drawn with GDI+ onto a 4x supersampled surface and then downsampled with
      high-quality interpolation: System.Drawing has no anti-aliased path fill at
      small sizes, and 48px is small enough that the arrow and the narrow barrier
      would otherwise alias into mush.
    #>
    param([int] $Px)

    $ss = 4                      # supersample factor
    $big = $Px * $ss

    $bmp = New-Object System.Drawing.Bitmap($big, $big, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    try {
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic

        # Navy plate - a legacy icon carries its own background.
        $g.Clear($Navy)

        $whiteBrush = New-Object System.Drawing.SolidBrush($White)
        $navyBrush = New-Object System.Drawing.SolidBrush($Navy)
        try {
            # ---- shield silhouette -------------------------------------------
            $top = ConvertTo-ScaledPoint -X $ShieldSpine -Y $ShieldTop -Px $big
            $left = ConvertTo-ScaledPoint -X ($ShieldSpine - $ShieldHalfWidth) -Y $ShieldShoulder -Px $big
            $right = ConvertTo-ScaledPoint -X ($ShieldSpine + $ShieldHalfWidth) -Y $ShieldShoulder -Px $big
            $leftLow = ConvertTo-ScaledPoint -X ($ShieldSpine - $ShieldHalfWidth) -Y $ShieldFlankBottom -Px $big
            $rightLow = ConvertTo-ScaledPoint -X ($ShieldSpine + $ShieldHalfWidth) -Y $ShieldFlankBottom -Px $big
            $tip = ConvertTo-ScaledPoint -X $ShieldSpine -Y $ShieldBottom -Px $big
            # Bezier controls, mirrored left/right, so the point stays symmetric.
            $rc1 = ConvertTo-ScaledPoint -X ($ShieldSpine + $ShieldHalfWidth) -Y $ShieldControlY -Px $big
            $rc2 = ConvertTo-ScaledPoint -X $ShieldControlX -Y $ShieldShoulderUpper -Px $big
            $lc1 = ConvertTo-ScaledPoint -X ($Canvas - $ShieldControlX) -Y $ShieldShoulderUpper -Px $big
            $lc2 = ConvertTo-ScaledPoint -X ($ShieldSpine - $ShieldHalfWidth) -Y $ShieldControlY -Px $big

            $shield = New-Object System.Drawing.Drawing2D.GraphicsPath
            try {
                $shield.AddLine($left, $right)          # top edge, shoulder to shoulder
                $shield.AddLine($right, $rightLow)      # right flank
                $shield.AddBezier($rightLow, $rc1, $rc2, $tip)
                $shield.AddBezier($tip, $lc1, $lc2, $leftLow)
                $shield.AddLine($leftLow, $left)        # left flank
                $shield.CloseFigure()
                $g.FillPath($whiteBrush, $shield)

                # ---- arrow shaft + head (navy, inside the shield) -------------
                $s1 = ConvertTo-ScaledPoint -X 33 -Y 50.5 -Px $big
                $s2 = ConvertTo-ScaledPoint -X 58 -Y 57.5 -Px $big
                $g.FillRectangle($navyBrush, $s1.X, $s1.Y, $s2.X - $s1.X, $s2.Y - $s1.Y)

                $h1 = ConvertTo-ScaledPoint -X 55 -Y 43.5 -Px $big
                $h2 = ConvertTo-ScaledPoint -X 70 -Y 54.0 -Px $big
                $h3 = ConvertTo-ScaledPoint -X 55 -Y 64.5 -Px $big
                $g.FillPolygon($navyBrush, @($h1, $h2, $h3))

                # ---- barrier: white bar, then a navy slash across it ----------
                $b1 = ConvertTo-ScaledPoint -X 57 -Y 38 -Px $big
                $b2 = ConvertTo-ScaledPoint -X 70 -Y 70 -Px $big
                $g.FillRectangle($whiteBrush, $b1.X, $b1.Y, $b2.X - $b1.X, $b2.Y - $b1.Y)

                $k1 = ConvertTo-ScaledPoint -X 54.0 -Y 36.0 -Px $big
                $k2 = ConvertTo-ScaledPoint -X 62.0 -Y 36.0 -Px $big
                $k3 = ConvertTo-ScaledPoint -X 68.0 -Y 72.0 -Px $big
                $k4 = ConvertTo-ScaledPoint -X 60.0 -Y 72.0 -Px $big
                $g.FillPolygon($navyBrush, @($k1, $k2, $k3, $k4))
            } finally {
                $shield.Dispose()
            }
        } finally {
            $whiteBrush.Dispose()
            $navyBrush.Dispose()
        }
    } finally {
        $g.Dispose()
    }

    # Downsample the supersampled surface to the final size.
    $out = New-Object System.Drawing.Bitmap($Px, $Px, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $og = [System.Drawing.Graphics]::FromImage($out)
    try {
        $og.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $og.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $rect = New-Object System.Drawing.Rectangle(0, 0, $Px, $Px)
        $og.DrawImage($bmp, $rect)
    } finally {
        $og.Dispose()
        $bmp.Dispose()
    }
    return $out
}

# ------------------------------------------------------------------- main
$written = 0
$skipped = 0

# ic_launcher_round.png is the same artwork: pre-API-26 launchers that ask for a
# round icon composite it themselves, and on API 26+ the adaptive icon handles
# both via android:roundIcon, so no separate circular masking is needed here.
$Names = @('ic_launcher.png', 'ic_launcher_round.png')

foreach ($entry in $Densities.GetEnumerator()) {
    $dir = Join-Path $OutputRoot $entry.Key

    $todo = @()
    foreach ($name in $Names) {
        $file = Join-Path $dir $name
        if ((Test-Path $file) -and -not $Force) {
            Write-Host ("skip  {0}  (exists; use -Force)" -f ($entry.Key + '/' + $name)) -ForegroundColor DarkGray
            $skipped++
            continue
        }
        $todo += $file
    }

    if ($todo.Count -eq 0) { continue }
    if (-not (Test-Path $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }

    # Render once per density and write both names - the pixels are identical.
    $bmp = New-LauncherBitmap -Px $entry.Value
    try {
        foreach ($file in $todo) {
            $bmp.Save($file, [System.Drawing.Imaging.ImageFormat]::Png)
            $kb = [math]::Round((Get-Item $file).Length / 1KB, 1)
            Write-Host ("write {0}  {1}x{1}px  {2} KB" -f ($entry.Key + '/' + (Split-Path $file -Leaf)), $entry.Value, $kb) -ForegroundColor Green
            $written++
        }
    } finally {
        $bmp.Dispose()
    }
}

Write-Host ''
Write-Host ("Done: {0} written, {1} skipped." -f $written, $skipped) -ForegroundColor Cyan
Write-Host 'Note: the adaptive icon (mipmap-anydpi-v26/ic_launcher.xml) takes priority' -ForegroundColor Gray
Write-Host '      on API 26+, so these bitmaps only matter on older devices/launchers.' -ForegroundColor Gray

