# Set Java 21 environment for Gradle
$jdkPath = "C:\Program Files\Eclipse Adoptium\jdk-21.0.13.10002-hotspot"

# Create a temporary environment with Java 21
$env:JAVA_HOME = $jdkPath
$env:PATH = "$jdkPath\bin;$env:PATH"

# Verify Java version
Write-Host "Java version: $(& $jdkPath\bin\java -version 2>&1)"
Write-Host "JAVA_HOME: $env:JAVA_HOST"

# Run Gradle wrapper
& "C:\Gradle\gradle-8.7\bin\gradle" wrapper --gradle-version 9.1.0