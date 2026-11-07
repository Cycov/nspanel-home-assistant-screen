# Reconnects adb to a screen after it restarted: finds its wireless-debugging port over mDNS (the app switches
# wireless debugging back on, with a new port each time) and moves adb to the fixed port 5555 until the next restart.
#   .\tools\adb-reconnect.ps1 192.168.1.50
param([Parameter(Mandatory = $true)][string]$Address)

$FixedPort = 5555
$TcpipSettleSeconds = 3

$service = adb mdns services | Select-String "_adb-tls-connect._tcp\s+$([regex]::Escape($Address)):(\d+)" | Select-Object -First 1
if (-not $service) {
    adb connect "${Address}:$FixedPort" #< already on 5555 (no restart since the last time)
    exit $LASTEXITCODE
}
$port = $service.Matches[0].Groups[1].Value
adb connect "${Address}:$port"
adb -s "${Address}:$port" tcpip $FixedPort
Start-Sleep -Seconds $TcpipSettleSeconds
adb connect "${Address}:$FixedPort"
