# Streamify Windows PowerShell Bootstrap
param (
    [string]$Port = "3000"
)

$env:PORT = $Port
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -Path "$ScriptDir\..\.."
node src\boot\streamify.mjs
