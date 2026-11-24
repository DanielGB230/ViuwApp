$ErrorActionPreference = 'SilentlyContinue'
$deadline = (Get-Date).AddMinutes(5)
while ((Get-Date) -lt $deadline) {
    $boot = adb -s emulator-5556 shell getprop sys.boot_completed 2>$null
    if ($boot -match '1') {
        Write-Output "BOOTED"
        exit 0
    }
    Start-Sleep -Seconds 5
}
Write-Output "TIMEOUT"
exit 1
