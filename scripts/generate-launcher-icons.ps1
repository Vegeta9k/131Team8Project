# Generates square, centered launcher mipmaps and adaptive-icon foreground from source PNG.
param(
    [string]$SourceImage = "$PSScriptRoot\..\app\src\main\res\drawable\temp_app_icon.png",
    [string]$ResRoot = "$PSScriptRoot\..\app\src\main\res"
)

Add-Type -AssemblyName System.Drawing

$srcBmp = [System.Drawing.Bitmap]::FromFile((Resolve-Path $SourceImage))
try {
    $w = $srcBmp.Width
    $h = $srcBmp.Height
    $edge = [Math]::Max($w, $h)
    $square = New-Object System.Drawing.Bitmap $edge, $edge
    $g = [System.Drawing.Graphics]::FromImage($square)
    $g.Clear([System.Drawing.Color]::FromArgb(255, 255, 255, 255))
    $x = [int](($edge - $w) / 2)
    $y = [int](($edge - $h) / 2)
    $g.DrawImage($srcBmp, $x, $y, $w, $h)
    $g.Dispose()

    function New-ResizedBitmap([System.Drawing.Bitmap]$src, [int]$target) {
        $dest = New-Object System.Drawing.Bitmap $target, $target
        $g2 = [System.Drawing.Graphics]::FromImage($dest)
        $g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g2.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $g2.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $g2.DrawImage($src, 0, 0, $target, $target)
        $g2.Dispose()
        return $dest
    }

    $mipSizes = @{
        "mipmap-mdpi"    = 48
        "mipmap-hdpi"    = 72
        "mipmap-xhdpi"   = 96
        "mipmap-xxhdpi"  = 144
        "mipmap-xxxhdpi" = 192
    }

    foreach ($kv in $mipSizes.GetEnumerator()) {
        $dir = Join-Path $ResRoot $kv.Key
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        $out = New-ResizedBitmap $square $kv.Value
        try {
            $out.Save((Join-Path $dir "ic_launcher.png"), [System.Drawing.Imaging.ImageFormat]::Png)
            $out.Save((Join-Path $dir "ic_launcher_round.png"), [System.Drawing.Imaging.ImageFormat]::Png)
        } finally {
            $out.Dispose()
        }
    }

    # High-res foreground for adaptive icon (scale-friendly)
    $fgSize = 432
    $foreground = New-ResizedBitmap $square $fgSize
    try {
        $fgPath = Join-Path $ResRoot "drawable\ic_launcher_foreground.png"
        New-Item -ItemType Directory -Force -Path (Split-Path $fgPath) | Out-Null
        $foreground.Save($fgPath, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $foreground.Dispose()
    }
} finally {
    $srcBmp.Dispose()
}

Write-Host "Launcher icons generated under $ResRoot"
