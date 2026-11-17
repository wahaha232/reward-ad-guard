# 編譯「手機螢幕開關.exe」：Windows 內建 .NET Framework 的 csc，內嵌 screenoff.dex 與圖示。
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$out = Join-Path $here '手機螢幕開關.exe'
& $csc /nologo /target:winexe /codepage:65001 /optimize+ "/out:$out" `
    "/win32icon:$(Join-Path $here 'app.ico')" `
    "/resource:$(Join-Path $here 'screenoff.dex'),screenoff.dex" `
    /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.Core.dll `
    (Join-Path $here 'ScreenSwitch.cs')
if ($LASTEXITCODE -ne 0) { throw "csc failed: $LASTEXITCODE" }
Write-Host "built $out"
