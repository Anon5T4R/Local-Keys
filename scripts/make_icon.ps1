# Desenha o icone do Local Keys (flat, squircle, gradiente indigo -> violeta,
# cadeado branco com buraco de fechadura vazado) + assinatura "Taylor" no pe do tile
# (estilo da suite), e gera icon.ico + icons/icon.png + icons/icon-1024.png.
Add-Type -AssemblyName System.Drawing

$root = "C:\Dev\underworld-apps\local-keys"
$size = 1024

function New-Canvas([int]$s) {
    $bmp = New-Object System.Drawing.Bitmap($s, $s)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    return @($bmp, $g)
}

function Squircle([float]$s, [float]$r) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddArc(0, 0, $r, $r, 180, 90)
    $path.AddArc($s - $r, 0, $r, $r, 270, 90)
    $path.AddArc($s - $r, $s - $r, $r, $r, 0, 90)
    $path.AddArc(0, $s - $r, $r, $r, 90, 90)
    $path.CloseFigure()
    return $path
}

$bmp, $g = New-Canvas $size

# fundo squircle com gradiente linear (indigo claro -> violeta)
$r = $size * 0.185
$sq = Squircle $size $r
$rect = New-Object System.Drawing.Rectangle(0, 0, $size, $size)
$grad = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, `
    [System.Drawing.Color]::FromArgb(255, 111, 124, 236), `
    [System.Drawing.Color]::FromArgb(255, 90, 70, 200), 60)
$g.FillPath($grad, $sq)

# arco do cadeado (branco, tracejado grosso) + pernas descendo pro corpo
$shacklePen = New-Object System.Drawing.Pen([System.Drawing.Brushes]::White, [float]($size * 0.068))
$shacklePen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
$shacklePen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
$arcRect = New-Object System.Drawing.Rectangle([int]($size * 0.354), [int]($size * 0.302), [int]($size * 0.292), [int]($size * 0.292))
$g.DrawArc($shacklePen, $arcRect, 180, 180)
$legL = [int]($size * 0.354); $legR = [int]($size * 0.646); $legTop = [int]($size * 0.448); $legBot = [int]($size * 0.55)
$g.DrawLine($shacklePen, $legL, $legTop, $legL, $legBot)
$g.DrawLine($shacklePen, $legR, $legTop, $legR, $legBot)
$shacklePen.Dispose()

# corpo do cadeado (retangulo arredondado branco)
$body = New-Object System.Drawing.Drawing2D.GraphicsPath
$bx = $size * 0.325; $by = $size * 0.532; $bw = $size * 0.35; $bh = $size * 0.26; $br = $size * 0.042
$body.AddArc($bx, $by, $br, $br, 180, 90)
$body.AddArc($bx + $bw - $br, $by, $br, $br, 270, 90)
$body.AddArc($bx + $bw - $br, $by + $bh - $br, $br, $br, 0, 90)
$body.AddArc($bx, $by + $bh - $br, $br, $br, 90, 90)
$body.CloseFigure()
$g.FillPath([System.Drawing.Brushes]::White, $body)

# buraco de fechadura (circulo + haste) vazado: repinta com o MESMO gradiente do fundo
$khR = $size * 0.051; $khX = $size * 0.5; $khY = $size * 0.650
$g.FillEllipse($grad, [float]($khX - $khR), [float]($khY - $khR), [float]($khR * 2), [float]($khR * 2))
$stemW = $size * 0.034
$g.FillRectangle($grad, [float]($khX - $stemW / 2), [float]$khY, [float]$stemW, [float]($size * 0.075))

# assinatura da marca (estilo da suite: Segoe Script, branco ~55%, no pe do tile)
$sigFont = New-Object System.Drawing.Font('Segoe Script', [float]($size * 0.082), [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$sigBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(140, 255, 255, 255))
$sigFormat = New-Object System.Drawing.StringFormat
$sigFormat.Alignment = [System.Drawing.StringAlignment]::Center
$g.DrawString('Taylor', $sigFont, $sigBrush, [float]($size / 2), [float]($size * 0.845), $sigFormat)
$sigFont.Dispose(); $sigBrush.Dispose(); $sigFormat.Dispose()

$g.Dispose()

# salva master 1024 (fonte pra futuros redraws)
$bmp.Save("$root\icons\icon-1024.png", [System.Drawing.Imaging.ImageFormat]::Png)

# icons/icon.png 256
$icon256 = New-Object System.Drawing.Bitmap($bmp, 256, 256)
$icon256.Save("$root\icons\icon.png", [System.Drawing.Imaging.ImageFormat]::Png)
$icon256.Dispose()

# icon.ico multi-tamanho (256/64/48/32/16)
$sizes = @(256, 64, 48, 32, 16)
$streams = @()
foreach ($s in $sizes) {
    $b = New-Object System.Drawing.Bitmap($bmp, $s, $s)
    $ms = New-Object System.IO.MemoryStream
    $b.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $b.Dispose()
    $streams += ,([byte[]]$ms.ToArray())
    $ms.Dispose()
}
$ico = New-Object System.IO.MemoryStream
$bw = New-Object System.IO.BinaryWriter($ico)
$bw.Write([uint16]0); $bw.Write([uint16]1); $bw.Write([uint16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $s = $sizes[$i]; $d = $streams[$i]
    $bw.Write([byte]$(if ($s -ge 256) { 0 } else { $s })); $bw.Write([byte]$(if ($s -ge 256) { 0 } else { $s }))
    $bw.Write([byte]0); $bw.Write([byte]0)
    $bw.Write([uint16]1); $bw.Write([uint16]32)
    $bw.Write([uint32]$d.Length); $bw.Write([uint32]$offset)
    $offset += $d.Length
}
foreach ($d in $streams) { $bw.Write($d) }
$bw.Flush()
[System.IO.File]::WriteAllBytes("$root\icon.ico", $ico.ToArray())
$bw.Dispose(); $ico.Dispose()

$bmp.Dispose()
"icones gerados: icon.ico ($((Get-Item $root\icon.ico).Length) bytes), icons/icon.png ($((Get-Item $root\icons\icon.png).Length) bytes), icons/icon-1024.png ($((Get-Item $root\icons\icon-1024.png).Length) bytes)"
