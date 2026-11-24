$ErrorActionPreference = 'SilentlyContinue'
$deadline = (Get-Date).AddMinutes(5)
while ((Get-Date) -lt $deadline) {
    adb -s emulator-5556 shell input keyevent KEYCODE_WAKEUP | Out-Null
    $out = adb -s emulator-5556 shell am start -n com.viuwapp.stream/com.example.streamapp.MainActivity 2>&1
    if ($out -match 'Starting: Intent' -and $out -notmatch 'Exception') {
        Write-Output "STARTED"
        exit 0
    }
    Start-Sleep -Seconds 8
}
Write-Output "START_TIMEOUT"
exit 1
