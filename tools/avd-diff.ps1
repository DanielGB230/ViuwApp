$root = Join-Path $env:USERPROFILE '.android\avd'
$a = Get-Content (Join-Path $root 'Medium_Phone.avd\config.ini')
$b = Get-Content (Join-Path $root 'ViuwReceiver.avd\config.ini')
Compare-Object $a $b | ForEach-Object { "{0} {1}" -f $_.SideIndicator, $_.InputObject }
