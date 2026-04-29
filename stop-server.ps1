# One-shot client that sends a Hello + Request{srv_stop} to the running
# Father Eye bridge over TCP. Used to gracefully halt the server when the
# panel UI is not driveable (e.g. during automated test sessions).
param(
    [string]$Host_ = "127.0.0.1",
    [int]$Port = 63729
)

$ErrorActionPreference = "Stop"

function Write-Frame {
    param([System.IO.Stream]$Stream, [string]$Json)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Json)
    $len = $bytes.Length
    $hdr = New-Object byte[] 5
    $hdr[0] = ($len -shr 24) -band 0xFF
    $hdr[1] = ($len -shr 16) -band 0xFF
    $hdr[2] = ($len -shr 8) -band 0xFF
    $hdr[3] = $len -band 0xFF
    $hdr[4] = 0  # codec=JSON
    $Stream.Write($hdr, 0, 5)
    $Stream.Write($bytes, 0, $bytes.Length)
    $Stream.Flush()
}

function Read-Frame {
    param([System.IO.Stream]$Stream)
    $hdr = New-Object byte[] 5
    $read = 0
    while ($read -lt 5) {
        $n = $Stream.Read($hdr, $read, 5 - $read)
        if ($n -le 0) { throw "stream closed before header" }
        $read += $n
    }
    $len = ([int]$hdr[0] -shl 24) -bor ([int]$hdr[1] -shl 16) -bor ([int]$hdr[2] -shl 8) -bor [int]$hdr[3]
    $codec = $hdr[4]
    $buf = New-Object byte[] $len
    $read = 0
    while ($read -lt $len) {
        $n = $Stream.Read($buf, $read, $len - $read)
        if ($n -le 0) { throw "stream closed before payload" }
        $read += $n
    }
    return [System.Text.Encoding]::UTF8.GetString($buf)
}

$client = New-Object System.Net.Sockets.TcpClient
$client.Connect($Host_, $Port)
$stream = $client.GetStream()

$hello = '{"protocolVersion":1,"id":1,"kind":"Hello","topic":null,"payload":{"protocolVersion":1,"panelVersion":"stop-helper","capabilities":[]}}'
Write-Host "[stop-server] sending Hello..."
Write-Frame $stream $hello

$welcomeJson = Read-Frame $stream
Write-Host "[stop-server] Welcome received: $($welcomeJson.Substring(0, [Math]::Min(160, $welcomeJson.Length)))..."

$stopReq = '{"protocolVersion":1,"id":2,"kind":"Request","topic":null,"payload":{"op":"srv_stop","args":{}}}'
Write-Host "[stop-server] sending srv_stop..."
Write-Frame $stream $stopReq

# Try to read response; bridge may close the socket as the server halts.
try {
    $resp = Read-Frame $stream
    Write-Host "[stop-server] Response: $resp"
} catch {
    Write-Host "[stop-server] (socket closed during shutdown, expected)"
}

$client.Close()
Write-Host "[stop-server] done."
