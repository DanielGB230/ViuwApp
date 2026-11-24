$jdk = "C:\Program Files\Eclipse Adoptium\jdk-21.0.13.10002-hotspot"
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"
Set-Location "D:\ViuwApp\android"
& "D:\ViuwApp\tools\gradle-dist\gradle-8.11.1\bin\gradle.bat" @args
exit $LASTEXITCODE
