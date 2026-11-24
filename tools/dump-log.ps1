param([string]$Device, [string]$Out)
adb -s $Device logcat -d -v time | Out-File -FilePath $Out -Encoding utf8
