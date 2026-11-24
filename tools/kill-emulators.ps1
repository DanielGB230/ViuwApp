$ErrorActionPreference = 'SilentlyContinue'
$lines = adb devices | Select-String 'emulator-'
foreach ($line in $lines) {
    $id = ($line.ToString() -split '\s+')[0]
    if ($id) {
        adb -s $id emu kill | Out-Null
    }
}
Start-Sleep -Seconds 8
Get-Process -Name 'qemu-system-x86_64' | Stop-Process -Force
Get-Process -Name 'emulator' | Stop-Process -Force
Start-Sleep -Seconds 3
adb devices
