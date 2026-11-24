$ErrorActionPreference = 'SilentlyContinue'
$deadline = (Get-Date).AddMinutes(4)
while ((Get-Date) -lt $deadline) {
    $out = adb -s emulator-5556 shell pm path android 2>$null
    if ($out -match 'package:') {
        Write-Output "PKG_READY"
        exit 0
    }
    Start-Sleep -Seconds 5
}
Write-Output "PKG_TIMEOUT"
exit 1
