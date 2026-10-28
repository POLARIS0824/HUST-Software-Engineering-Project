$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$assetRoot = Join-Path $taskRoot 'android/app/src/main/assets/editor'
$fontRoot = Join-Path $taskRoot 'android/app/src/main/res/font'
$iconRoot = Join-Path $taskRoot 'android/app/src/main/res/drawable'
$cacheRoot = Join-Path $taskRoot '.cache'
New-Item -ItemType Directory -Force -Path $assetRoot,$fontRoot,$iconRoot,$cacheRoot | Out-Null
$package = Invoke-RestMethod 'https://registry.npmjs.org/vditor/3.9.9'
$archive = Join-Path $cacheRoot 'vditor-3.9.9.tgz'
Invoke-WebRequest -Uri $package.dist.tarball -OutFile $archive
$actual = (Get-FileHash -LiteralPath $archive -Algorithm SHA512).Hash
$expected = [Convert]::ToHexString([Convert]::FromBase64String($package.dist.integrity.Substring(7)))
if ($actual -ne $expected) { throw 'Vditor npm integrity mismatch' }
& tar -xzf $archive -C $cacheRoot package/dist package/LICENSE
if ($LASTEXITCODE -ne 0) { throw 'Vditor extraction failed' }
New-Item -ItemType Directory -Force -Path (Join-Path $assetRoot 'vditor/dist') | Out-Null
Get-ChildItem -LiteralPath (Join-Path $cacheRoot 'package/dist') | Copy-Item -Destination (Join-Path $assetRoot 'vditor/dist') -Recurse -Force
Copy-Item -LiteralPath (Join-Path $cacheRoot 'package/LICENSE') -Destination (Join-Path $assetRoot 'VDITOR-LICENSE') -Force
$fontUrl = 'https://raw.githubusercontent.com/google/fonts/main/ofl/googlesansflex/GoogleSansFlex%5BROND%2Copsz%2Cslnt%2Cwdth%2Cwght%5D.ttf'
Invoke-WebRequest -Uri $fontUrl -OutFile (Join-Path $fontRoot 'google_sans_flex.ttf')
Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/google/fonts/main/ofl/googlesansflex/OFL.txt' -OutFile (Join-Path $assetRoot 'GOOGLE-SANS-FLEX-OFL.txt')
$icons = @('search','edit_square','arrow_back','more_horiz','folder','description','sell','hub','add','tune','link','science','check','close','dark_mode','light_mode')
foreach ($icon in $icons) {
  $url = "https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/$icon/materialsymbolsrounded/$($icon)_24px.xml"
  Invoke-WebRequest -Uri $url -OutFile (Join-Path $iconRoot "sym_$icon.xml")
  $iconFile = Join-Path $iconRoot "sym_$icon.xml"
  (Get-Content -Raw -LiteralPath $iconFile).Replace('?attr/colorControlNormal', '?android:attr/colorControlNormal') | Set-Content -Encoding utf8 -LiteralPath $iconFile
}
$lock = [ordered]@{
  vditorVersion = $package.version
  vditorTarball = $package.dist.tarball
  vditorIntegrity = $package.dist.integrity
  vditorJsSha256 = (Get-FileHash (Join-Path $assetRoot 'vditor/dist/index.min.js') -Algorithm SHA256).Hash
  fontSource = $fontUrl
  fontSha256 = (Get-FileHash (Join-Path $fontRoot 'google_sans_flex.ttf') -Algorithm SHA256).Hash
}
$lock | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $taskRoot 'assets-lock.json')
Copy-Item -LiteralPath (Join-Path $taskRoot 'assets-lock.json') -Destination (Join-Path $assetRoot 'assets-lock.json') -Force
$lock | ConvertTo-Json
