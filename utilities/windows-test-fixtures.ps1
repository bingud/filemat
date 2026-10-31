# Creates the test files for docs/windows-test-plan.md.
# Run in a normal (not administrator) PowerShell window:
#   powershell -ExecutionPolicy Bypass -File .\utilities\windows-test-fixtures.ps1
# Symbolic links need Developer Mode (Settings -> For developers). Without it they are skipped with a warning.
# Delete the fixtures with: cmd /c rmdir /s /q C:\FmTest   (rmdir removes junctions without entering them)
param(
    [string]$Root = "C:\FmTest",
    # ffmpeg.exe for the test videos and images. Default: FM_FFMPEG_PATH, then PATH
    [string]$Ffmpeg = $env:FM_FFMPEG_PATH,
    # Also write a 430 MB TIFF whose header is at the end (large image metadata test)
    [switch]$BigTiff
)
$ErrorActionPreference = "Stop"
$warnings = New-Object System.Collections.Generic.List[string]

if (Test-Path -LiteralPath $Root) { throw "$Root already exists. Delete it first, or pass another -Root." }
if (-not $Ffmpeg) { $Ffmpeg = (Get-Command ffmpeg.exe -ErrorAction SilentlyContinue).Source }
if (-not $Ffmpeg) { $warnings.Add("No ffmpeg.exe found (pass -Ffmpeg): no test videos or images.") }

function Add-Folder([string]$path) { [System.IO.Directory]::CreateDirectory($path) | Out-Null; return $path }
function Add-Text([string]$path, [string]$text) { [System.IO.File]::WriteAllText($path, $text) }
function Invoke-Ffmpeg([string[]]$arguments) {
    if (-not $Ffmpeg) { return }
    & $Ffmpeg -hide_banner -loglevel error -y @arguments
    if ($LASTEXITCODE -ne 0) { $warnings.Add("ffmpeg failed: $arguments") }
}
function Invoke-Cmd([string]$command, [string]$what) {
    cmd.exe /c "$command 2>&1" | Out-Null
    if ($LASTEXITCODE -ne 0) { $warnings.Add("$what failed: $command") }
    return $LASTEXITCODE -eq 0
}

# Non-ASCII names are built from code points, so this file reads the same in any code page
$cjk = [string][char]0x5199 + [char]0x771F                                   # 写真
$latin = "" + [char]0xDC + "n" + [char]0xEF + "c" + [char]0xF6 + "d" + [char]0xE9  # Ünïcödé

Add-Folder $Root | Out-Null

# Media
$media = Add-Folder "$Root\media"
Invoke-Ffmpeg @("-f", "lavfi", "-i", "testsrc=duration=5:size=640x360:rate=25", "-pix_fmt", "yuv420p", "$media\video.mp4")
Invoke-Ffmpeg @("-f", "lavfi", "-i", "testsrc=size=640x360", "-frames:v", "1", "$media\image.png")
if ($BigTiff) { Invoke-Ffmpeg @("-f", "lavfi", "-i", "color=c=blue:s=12000x12000", "-frames:v", "1", "$media\big.tiff") }

# Names that need URL encoding
$names = Add-Folder "$Root\names"
foreach ($n in @("a#b.txt", "50%.txt", "a&b.txt", "a+b.txt", "a b.txt", "$latin.txt")) { Add-Text "$names\$n" "name test: $n" }
if ($Ffmpeg) { Copy-Item -LiteralPath "$media\image.png" -Destination "$names\$cjk.png" } else { Add-Text "$names\$cjk.txt" "cjk" }

# Prefix look-alikes: a permission on one must not apply to the others
$prefix = Add-Folder "$Root\prefix"
foreach ($n in @("a_b", "a%b", "aXb", "foo", "foobar")) { Add-Folder "$prefix\$n" | Out-Null; Add-Text "$prefix\$n\inside.txt" $n }

# Case
$case = Add-Folder "$Root\case"
Add-Text "$case\Report.txt" "one file, any case"
$cs = Add-Folder "$Root\cs"
if (Invoke-Cmd "fsutil file setCaseSensitiveInfo `"$cs`" enable" "Case-sensitive folder") {
    Add-Text "$cs\a.txt" "lower"
    Add-Text "$cs\A.txt" "UPPER"
}

# Read-only
$ro = Add-Folder "$Root\readonly"
Add-Text "$ro\ro.txt" "read-only"
Add-Folder "$ro\mixed" | Out-Null
foreach ($n in @("ro.txt", "a.txt", "b.txt")) { Add-Text "$ro\mixed\$n" $n }
foreach ($f in @("$ro\ro.txt", "$ro\mixed\ro.txt")) { Set-ItemProperty -LiteralPath $f -Name IsReadOnly -Value $true }

# Junctions: the target's files must survive anything done to the parent
$j = Add-Folder "$Root\junction"
Add-Folder "$j\target\sub" | Out-Null
Add-Text "$j\target\keep1.txt" "must survive"
Add-Text "$j\target\sub\keep2.txt" "must survive"
Add-Folder "$j\parent" | Out-Null
Add-Text "$j\parent\file.txt" "parent file"
Invoke-Cmd "mklink /J `"$j\parent\link`" `"$j\target`"" "Junction" | Out-Null
Add-Folder "$j\upload" | Out-Null
Invoke-Cmd "mklink /J `"$j\upload\samename`" `"$j\target`"" "Junction" | Out-Null

# Symbolic links (Developer Mode). 192.0.2.1 is a documentation address that never answers.
$links = Add-Folder "$Root\links"
Add-Text "$links\real.txt" "link target"
if (Invoke-Cmd "mklink `"$links\local-link.txt`" `"$links\real.txt`"" "Symbolic link (needs Developer Mode)") {
    Invoke-Cmd "mklink /D `"$links\network-link`" \\192.0.2.1\share" "Symbolic link to a network share" | Out-Null
}

# Hard link: one file, two paths
$hard = Add-Folder "$Root\hardlink"
Add-Text "$hard\original.txt" "hard-linked"
Invoke-Cmd "mklink /H `"$hard\second.txt`" `"$hard\original.txt`"" "Hard link" | Out-Null

# A path of about 300 characters
$long = $Root + "\long"
while ($long.Length -lt 260) { $long += "\" + ("x" * 40) }
Add-Folder "\\?\$long" | Out-Null
Add-Text "\\?\$long\long-name-text-file.txt" "long path"
if ($Ffmpeg) {
    [System.IO.File]::Copy("$media\video.mp4", "\\?\$long\video.mp4")
    [System.IO.File]::Copy("$media\image.png", "\\?\$long\image.png")
}

# Playlist that lists another folder's video. Expose public only to the test user.
$private = Add-Folder "$Root\private"
$public = Add-Folder "$Root\public"
Invoke-Ffmpeg @("-f", "lavfi", "-i", "color=c=red:s=320x240:d=3", "-f", "mpegts", "$private\secret.ts")
Add-Text "$public\evil.m3u8" "#EXTM3U`n#EXT-X-VERSION:3`n#EXT-X-TARGETDURATION:3`n#EXTINF:3.0,`n../private/secret.ts`n#EXT-X-ENDLIST`n"

# A blocked folder with a long name, for the 8.3 short name test
$hidden = Add-Folder "$Root\blocked\LongSecretFolder"
Add-Text "$hidden\secret.txt" "must stay blocked"
$shortName = (New-Object -ComObject Scripting.FileSystemObject).GetFolder($hidden).ShortPath
if ($shortName -eq $hidden) { $warnings.Add("No 8.3 short name on this drive (fsutil 8dot3name query $($Root.Substring(0, 2))). The short name test can't run here.") }

# Tree for zip download, folder size and search
$tree = Add-Folder "$Root\tree"
foreach ($d in @("one", "one\two", "one\two\three", "a#b")) { Add-Folder "$tree\$d" | Out-Null; Add-Text "$tree\$d\file.txt" $d }

Write-Host ""
Write-Host "Fixtures in $Root"
Write-Host "Short name of $hidden : $shortName"
Write-Host "Long folder ($($long.Length) characters): $long"
foreach ($w in $warnings) { Write-Warning $w }
