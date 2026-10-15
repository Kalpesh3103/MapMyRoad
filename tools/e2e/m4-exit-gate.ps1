<#
.SYNOPSIS
    M4 exit gate check (IMPLEMENTATION_PLAN.md M4, TASKS.md): synthetic uploads from several devices flow through
    map matching and aggregation into the hazards API, the vector tiles and route scoring, on a local deployment.

.DESCRIPTION
    Runs against the local stack only: the backend started with the `local` profile (Play Integrity off), PostGIS,
    Valhalla with the dev extract (infra/scripts/load-dev-data.sh). It never targets a real server: -BaseUrl must be
    localhost or 127.0.0.1.

    Steps:
      1. Baseline: GET /v1/config, a route from Shivajinagar to Swargate (POST /v1/routes), and the map tile at the
         chosen bump (z14).
      2. Decode the route shape and place a synthetic severe bump in the middle of it, plus rough 50 m windows over
         about 500 m around the bump. The points lie on the road Valhalla returned, so map matching can snap them.
      3. Register 3 test devices and upload the same bump and windows from each (3 devices are needed on low-traffic
         roads, SECURITY.md T2).
      4. Wait for the matching job (every minute) and the aggregation job (every 5 minutes) and poll GET /v1/hazards
         until the bump is visible.
      5. Check the tile at the bump grew, and the same route now has a lower comfort score and one more severe hazard.
      6. Clean up: delete the 3 devices (DELETE /v1/devices/me) and check the hazard disappears after the recompute
         job (PRD PV-3).

    Coordinates are never printed, only distances and counts (AGENTS.md section 6).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\e2e\m4-exit-gate.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\e2e\m4-exit-gate.ps1 -KeepData
#>
[CmdletBinding()]
param(
    [string] $BaseUrl = "http://localhost:8080",
    [string] $AppVersion = "1.0.0",
    # Long enough for matching (1 min settle + 1 min interval) and aggregation (5 min interval) with margin.
    [int] $WaitMinutes = 12,
    # The recompute job runs every 2 minutes.
    [int] $CleanupWaitMinutes = 5,
    # Leave the test devices and their data in the database (for inspecting the map by hand).
    [switch] $KeepData,
    [switch] $SkipCleanupCheck
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"
$Invariant = [System.Globalization.CultureInfo]::InvariantCulture

if ($BaseUrl -notmatch '^https?://(localhost|127\.0\.0\.1)(:\d+)?$') {
    Write-Error "Refusing: -BaseUrl must be localhost or 127.0.0.1. This script creates test devices and data."
}

# Fixed public places in central Pune, inside the dev extract (infra/osm/pilot-area.env DEV_BBOX).
$Origin = @{ lat = 18.5308; lon = 73.8475 }       # Shivajinagar
$Destination = @{ lat = 18.5018; lon = 73.8636 }  # Swargate
$VehicleType = "scooter"
$SpeedKmh = 30.0
$WindowLengthM = 50.0
$StretchHalfM = 250.0
$BumpRoughness = 1.8   # well above the "poor" end of the score curve (2.0), so the segment score is low

$Results = New-Object System.Collections.Generic.List[object]
function Add-Result([string] $Check, [bool] $Passed, [string] $Detail) {
    $Results.Add([pscustomobject]@{ Check = $Check; Result = $(if ($Passed) { "PASS" } else { "FAIL" }); Detail = $Detail })
    $color = if ($Passed) { "Green" } else { "Red" }
    Write-Host ("[{0}] {1} - {2}" -f $(if ($Passed) { "PASS" } else { "FAIL" }), $Check, $Detail) -ForegroundColor $color
}

function Write-Step([string] $Text) { Write-Host ""; Write-Host "== $Text" -ForegroundColor Cyan }

# --- HTTP helper (works in Windows PowerShell 5.1 and PowerShell 7) --------------------------------------------
function Invoke-Api {
    param(
        [string] $Method,
        [string] $Path,
        $Body = $null,
        [string] $Token = $null,
        [switch] $Binary
    )
    $headers = @{ "X-App-Version" = $AppVersion }
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }
    $params = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $headers; UseBasicParsing = $true }
    if ($null -ne $Body) {
        $params["Body"] = ConvertTo-Json -InputObject $Body -Depth 10 -Compress
        $params["ContentType"] = "application/json"
    }
    try {
        $response = Invoke-WebRequest @params
    } catch {
        $status = $null
        if ($_.Exception.Response) { $status = [int] $_.Exception.Response.StatusCode }
        $detail = $_.ErrorDetails.Message
        throw ("HTTP {0} on {1} {2}: {3}" -f $status, $Method, $Path, $detail)
    }
    $result = [pscustomobject]@{ Status = [int] $response.StatusCode; Json = $null; Length = 0 }
    if ($Binary) {
        if ($response.RawContentStream) { $result.Length = [int] $response.RawContentStream.Length }
    } elseif ($response.Content) {
        $text = $response.Content
        if ($text -is [byte[]]) { $text = [System.Text.Encoding]::UTF8.GetString($text) }
        if ($text.Trim().Length -gt 0) { $result.Json = $text | ConvertFrom-Json }
    }
    return $result
}

# --- Geometry helpers -------------------------------------------------------------------------------------------
function Get-DistanceM([double] $lat1, [double] $lon1, [double] $lat2, [double] $lon2) {
    $r = 6371008.8
    $dLat = ($lat2 - $lat1) * [math]::PI / 180
    $dLon = ($lon2 - $lon1) * [math]::PI / 180
    $a = [math]::Pow([math]::Sin($dLat / 2), 2) +
         [math]::Cos($lat1 * [math]::PI / 180) * [math]::Cos($lat2 * [math]::PI / 180) * [math]::Pow([math]::Sin($dLon / 2), 2)
    return 2 * $r * [math]::Asin([math]::Min(1.0, [math]::Sqrt($a)))
}

# Google encoded polyline with precision 6 (Valhalla's native format, TECH_STACK.md section 5).
function ConvertFrom-Polyline6([string] $Encoded) {
    $points = New-Object System.Collections.Generic.List[object]
    $index = 0
    [long] $lat = 0
    [long] $lon = 0
    while ($index -lt $Encoded.Length) {
        $deltas = @(0, 0)
        for ($c = 0; $c -lt 2; $c++) {
            [long] $value = 0
            $shift = 0
            do {
                $b = [int][char] $Encoded[$index] - 63
                $index++
                $value = $value -bor ([long]($b -band 0x1f) -shl $shift)
                $shift += 5
            } while ($b -ge 0x20)
            if (($value -band 1) -eq 1) { $deltas[$c] = - ($value -shr 1) - 1 } else { $deltas[$c] = $value -shr 1 }
        }
        $lat += $deltas[0]
        $lon += $deltas[1]
        $points.Add([pscustomobject]@{ Lat = $lat / 1e6; Lon = $lon / 1e6 })
    }
    return , $points
}

# Point at a distance along the polyline, by linear interpolation between vertices.
function Get-PointAlong($Points, [double[]] $Cumulative, [double] $DistanceM) {
    if ($DistanceM -le 0) { return $Points[0] }
    for ($i = 1; $i -lt $Points.Count; $i++) {
        if ($Cumulative[$i] -ge $DistanceM) {
            $segment = $Cumulative[$i] - $Cumulative[$i - 1]
            $t = 0.0
            if ($segment -gt 0) { $t = ($DistanceM - $Cumulative[$i - 1]) / $segment }
            return [pscustomobject]@{
                Lat = $Points[$i - 1].Lat + $t * ($Points[$i].Lat - $Points[$i - 1].Lat)
                Lon = $Points[$i - 1].Lon + $t * ($Points[$i].Lon - $Points[$i - 1].Lon)
            }
        }
    }
    return $Points[$Points.Count - 1]
}

function Get-TileXY([double] $Lat, [double] $Lon, [int] $Zoom) {
    $n = [math]::Pow(2, $Zoom)
    $x = [math]::Floor(($Lon + 180.0) / 360.0 * $n)
    $latRad = $Lat * [math]::PI / 180
    $y = [math]::Floor((1.0 - [math]::Log([math]::Tan($latRad) + 1.0 / [math]::Cos($latRad)) / [math]::PI) / 2.0 * $n)
    return @([int] $x, [int] $y)
}

function Format-Instant([DateTime] $Utc) { return $Utc.ToString("yyyy-MM-ddTHH:mm:ss.fffZ", $Invariant) }

function Find-TestHazard($Token, $Bump) {
    $d = 0.003
    $bbox = "{0},{1},{2},{3}" -f ($Bump.Lon - $d).ToString($Invariant), ($Bump.Lat - $d).ToString($Invariant),
                                 ($Bump.Lon + $d).ToString($Invariant), ($Bump.Lat + $d).ToString($Invariant)
    $response = Invoke-Api -Method GET -Path "/v1/hazards?bbox=$bbox&minSeverity=3" -Token $Token
    foreach ($feature in @($response.Json.features)) {
        if ($null -eq $feature) { continue }
        $lon = [double] $feature.geometry.coordinates[0]
        $lat = [double] $feature.geometry.coordinates[1]
        if ((Get-DistanceM $lat $lon $Bump.Lat $Bump.Lon) -le 20) { return $feature }
    }
    return $null
}

function Register-Device {
    $challenge = Invoke-Api -Method POST -Path "/v1/devices/challenge"
    $body = @{ integrityToken = "local-test"; nonce = $challenge.Json.nonce; appVersion = $AppVersion; platform = "android"; osApiLevel = 34 }
    return (Invoke-Api -Method POST -Path "/v1/devices" -Body $body).Json
}

function Find-Candidate($Routes, [string] $Geometry) {
    foreach ($candidate in @($Routes.candidates)) {
        if ($candidate.geometry -eq $Geometry) { return $candidate }
    }
    return $null
}

$devices = New-Object System.Collections.Generic.List[object]
$exitCode = 0
try {
    # --- 1. Baseline ----------------------------------------------------------------------------------------------
    Write-Step "1. Baseline"
    $config = Invoke-Api -Method GET -Path "/v1/config"
    Add-Result "Backend reachable" ($config.Status -eq 200) "GET /v1/config returned $($config.Status)"

    $probe = Register-Device
    $devices.Add($probe)
    $routeBody = @{ origin = $Origin; destination = $Destination; vehicleType = $VehicleType; preference = 0.5 }
    $baselineRoutes = (Invoke-Api -Method POST -Path "/v1/routes" -Body $routeBody -Token $probe.accessToken).Json
    $baseline = @($baselineRoutes.candidates) | Where-Object { $_.labels -contains "fastest" } | Select-Object -First 1
    if ($null -eq $baseline) { $baseline = @($baselineRoutes.candidates)[0] }
    Add-Result "Baseline route" ($null -ne $baseline) ("{0} candidate(s); fastest comfort {1}, severe hazards {2}" -f @($baselineRoutes.candidates).Count, $baseline.comfortScore, $baseline.hazards.severe)

    # --- 2. Place the synthetic bump on the route --------------------------------------------------------------------
    Write-Step "2. Synthetic bump on the route"
    $points = ConvertFrom-Polyline6 $baseline.geometry
    $cumulative = New-Object double[] $points.Count
    for ($i = 1; $i -lt $points.Count; $i++) {
        $cumulative[$i] = $cumulative[$i - 1] + (Get-DistanceM $points[$i - 1].Lat $points[$i - 1].Lon $points[$i].Lat $points[$i].Lon)
    }
    $routeLengthM = $cumulative[$points.Count - 1]
    $bumpAtM = $routeLengthM / 2
    $bump = Get-PointAlong $points $cumulative $bumpAtM
    $stretchStartM = [math]::Max(0, $bumpAtM - $StretchHalfM)
    $stretchEndM = [math]::Min($routeLengthM, $bumpAtM + $StretchHalfM)
    Add-Result "Route shape decoded" ($points.Count -ge 2 -and $routeLengthM -gt 1000) ("{0} vertices, {1} m; bump at {2} m along the route" -f $points.Count, [math]::Round($routeLengthM), [math]::Round($bumpAtM))

    $tile = Get-TileXY $bump.Lat $bump.Lon 14
    $tilePath = "/v1/tiles/14/{0}/{1}.mvt" -f $tile[0], $tile[1]
    $baselineTile = Invoke-Api -Method GET -Path $tilePath -Binary
    Write-Host ("Baseline tile {0}: HTTP {1}, {2} bytes" -f $tilePath, $baselineTile.Status, $baselineTile.Length)

    # --- 3. Three devices upload the same bump -----------------------------------------------------------------------
    Write-Step "3. Uploads from 3 devices"
    $speedMps = $SpeedKmh / 3.6
    for ($k = 0; $k -lt 3; $k++) {
        $device = if ($k -eq 0) { $probe } else { Register-Device }
        if ($k -gt 0) { $devices.Add($device) }
        # Each device passes 4 minutes apart, ending at least 1 minute in the past (uploads may not be in the future).
        $passStart = (Get-Date).ToUniversalTime().AddSeconds(-60 - (3 - $k) * 240 - ($stretchEndM - $stretchStartM) / $speedMps)
        $windows = New-Object System.Collections.Generic.List[object]
        for ($from = $stretchStartM; $from + 20 -le $stretchEndM; $from += $WindowLengthM) {
            $to = [math]::Min($from + $WindowLengthM, $stretchEndM)
            $start = Get-PointAlong $points $cumulative $from
            $end = Get-PointAlong $points $cumulative $to
            $lengthM = Get-DistanceM $start.Lat $start.Lon $end.Lat $end.Lon
            if ($lengthM -lt 20 -or $lengthM -gt 120) { continue }   # API_SPEC 5.3 window length limits
            $windows.Add(@{
                id = [guid]::NewGuid().ToString()
                startLat = $start.Lat; startLon = $start.Lon; endLat = $end.Lat; endLon = $end.Lon
                observedAt = Format-Instant ($passStart.AddSeconds(($to - $stretchStartM) / $speedMps))
                lengthM = [math]::Round($lengthM, 1)
                roughness = $BumpRoughness
                speedKmh = $SpeedKmh
            })
        }
        $bumpEvent = @{
            id = [guid]::NewGuid().ToString()
            lat = $bump.Lat; lon = $bump.Lon
            observedAt = Format-Instant ($passStart.AddSeconds(($bumpAtM - $stretchStartM) / $speedMps))
            severity = 3
            speedKmh = $SpeedKmh
            peakScore = 8.5
        }
        $upload = @{
            tripToken = [guid]::NewGuid().ToString()
            vehicleType = $VehicleType
            events = @($bumpEvent)
            windows = $windows.ToArray()
        }
        $result = (Invoke-Api -Method POST -Path "/v1/events" -Body $upload -Token $device.accessToken).Json
        $rejected = @($result.rejected).Count
        Add-Result ("Upload from device {0}" -f ($k + 1)) ($result.accepted.events -eq 1 -and $rejected -eq 0) ("accepted {0} event(s), {1} window(s); rejected {2}" -f $result.accepted.events, $result.accepted.windows, $rejected)
    }

    # --- 4. Wait for matching and aggregation ------------------------------------------------------------------------
    Write-Step "4. Waiting for matching and aggregation (up to $WaitMinutes min)"
    $deadline = (Get-Date).AddMinutes($WaitMinutes)
    $hazard = $null
    while ((Get-Date) -lt $deadline) {
        $hazard = Find-TestHazard $probe.accessToken $bump
        if ($null -ne $hazard) { break }
        Write-Host ("  not visible yet, {0:N0} s left" -f ($deadline - (Get-Date)).TotalSeconds)
        Start-Sleep -Seconds 30
    }
    if ($null -ne $hazard) {
        Add-Result "Hazard visible in /v1/hazards" $true ("severity {0}, confidence {1}, status {2}" -f $hazard.properties.severity, $hazard.properties.confidence, $hazard.properties.status)
    } else {
        Add-Result "Hazard visible in /v1/hazards" $false "not visible after $WaitMinutes min (check that the matching and aggregation jobs run, and Valhalla is up)"
    }

    # --- 5. Tile and route scoring -----------------------------------------------------------------------------------
    Write-Step "5. Tiles and route scoring"
    # The server caches tiles for 60 s; the polling above already waited longer than that.
    $afterTile = Invoke-Api -Method GET -Path $tilePath -Binary
    Add-Result "Tile at the bump has more data" ($afterTile.Status -eq 200 -and $afterTile.Length -gt $baselineTile.Length) ("HTTP {0}, {1} bytes (baseline {2})" -f $afterTile.Status, $afterTile.Length, $baselineTile.Length)

    $afterRoutes = (Invoke-Api -Method POST -Path "/v1/routes" -Body $routeBody -Token $probe.accessToken).Json
    $same = Find-Candidate $afterRoutes $baseline.geometry
    if ($null -eq $same) {
        Add-Result "Route comfort dropped" $false "the baseline route shape is no longer among the candidates; compare by hand"
    } else {
        Add-Result "Route comfort dropped" ($same.comfortScore -lt $baseline.comfortScore) ("comfort {0} -> {1}" -f $baseline.comfortScore, $same.comfortScore)
        Add-Result "Route counts the new severe hazard" ($same.hazards.severe -ge $baseline.hazards.severe + 1) ("severe hazards {0} -> {1}; coverage {2}" -f $baseline.hazards.severe, $same.hazards.severe, $same.coverage.level)
    }
} catch {
    Add-Result "Unexpected error" $false $_.Exception.Message
} finally {
    # --- 6. Clean up -------------------------------------------------------------------------------------------------
    if ($KeepData) {
        Write-Host ""
        Write-Host "Keeping the $($devices.Count) test device(s) and their data (-KeepData)." -ForegroundColor Yellow
    } elseif ($devices.Count -gt 0) {
        Write-Step "6. Clean up: delete my data for the test devices"
        $deleted = 0
        foreach ($device in $devices) {
            try {
                $null = Invoke-Api -Method DELETE -Path "/v1/devices/me" -Token $device.accessToken
                $deleted++
            } catch {
                Write-Host "  delete failed: $($_.Exception.Message)" -ForegroundColor Yellow
            }
        }
        Add-Result "Test devices deleted" ($deleted -eq $devices.Count) "$deleted of $($devices.Count)"

        if (-not $SkipCleanupCheck -and (Get-Variable -Name bump -ErrorAction SilentlyContinue) -and $null -ne $bump) {
            Write-Host "Waiting for the recompute job to remove the hazard (up to $CleanupWaitMinutes min)"
            $checker = Register-Device
            $deadline = (Get-Date).AddMinutes($CleanupWaitMinutes)
            $gone = $false
            while ((Get-Date) -lt $deadline) {
                if ($null -eq (Find-TestHazard $checker.accessToken $bump)) { $gone = $true; break }
                Start-Sleep -Seconds 30
            }
            Add-Result "Hazard removed after deletion (PV-3)" $gone $(if ($gone) { "no longer visible" } else { "still visible after $CleanupWaitMinutes min" })
            try { $null = Invoke-Api -Method DELETE -Path "/v1/devices/me" -Token $checker.accessToken } catch { }
        }
    }

    Write-Host ""
    Write-Host "== Summary" -ForegroundColor Cyan
    $Results | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
    if (@($Results | Where-Object { $_.Result -eq "FAIL" }).Count -gt 0) {
        Write-Host "M4 exit gate: NOT met" -ForegroundColor Red
        $exitCode = 1
    } else {
        Write-Host "M4 exit gate: met" -ForegroundColor Green
    }
}
exit $exitCode
