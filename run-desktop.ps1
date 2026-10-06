# Launches HoloOS on Windows. Pass -Windowed for a normal window instead of fullscreen.
param([switch]$Windowed)
# Java's local sockets fail under the 8.3 short temp path (C:\Users\ZAINAL~1\...), so use a plain one.
New-Item -ItemType Directory -Force D:\tmp | Out-Null
$env:TEMP = 'D:\tmp'; $env:TMP = 'D:\tmp'
$env:GRADLE_OPTS = '-Djdk.net.unixdomain.tmpdir=D:\tmp'
if ($Windowed) { $env:HOLOOS_WINDOWED = '1' }
& "$PSScriptRoot\gradlew.bat" :shared:run
