# PowerShell wrapper for File Distributor (fixes Unicode args & sets working dir)

param([Parameter(ValueFromRemainingArguments=$true)] [string[]]$Paths)

$java = "C:\Program Files\Java\jdk-21\bin\javaw.exe"
$jar  = "C:\Users\ASUS\VisualStudioCodeProjects\file-distributor\file-distributor.jar"

# ensure working dir = jar's folder (so config.json is found)
Set-Location (Split-Path $jar)

# Pass args as an array so quoting & Unicode are preserved
$argList = @('-jar', $jar) + $Paths
Start-Process -FilePath $java -ArgumentList $argList -WindowStyle Hidden