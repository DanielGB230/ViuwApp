@echo off
set SDK=C:\Users\Dani\AppData\Local\Android\Sdk
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.13.10002-hotspot
echo no| "%SDK%\cmdline-tools\latest\bin\avdmanager.bat" create avd -n ViuwReceiver -k "system-images;android-36.1;google_apis_playstore;x86_64" -d pixel_6 --force
