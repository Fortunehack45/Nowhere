package com.fakegps.mocklocation.hotspot

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.*
import java.net.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Hotspot Location & GPS Tethering Server.
 * Runs an embedded lightweight HTTP server (port 8088) and NMEA 0183 TCP server (port 10110).
 * Allows any device connected to this device's Wi-Fi Hotspot (iPhones, iPads, PCs, Macs, Androids, car consoles)
 * to receive, synchronize with, and emulate the exact spoofed GPS location in real time.
 */
object HotspotLocationServer {

    private const val TAG = "HotspotLocationServer"
    const val DEFAULT_HTTP_PORT = 8088
    const val DEFAULT_NMEA_PORT = 10110

    @Volatile
    private var currentLat: Double = 37.774929
    @Volatile
    private var currentLon: Double = -122.419416
    @Volatile
    private var currentAlt: Double = 15.0
    @Volatile
    private var currentSpeedMps: Float = 0.0f
    @Volatile
    private var currentBearingDeg: Float = 0.0f
    @Volatile
    private var lastUpdateTimestamp: Long = System.currentTimeMillis()

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _connectedClientsCount = MutableStateFlow(0)
    val connectedClientsCount: StateFlow<Int> = _connectedClientsCount.asStateFlow()

    private val _serverUrl = MutableStateFlow("http://192.168.43.1:8088")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private var serverJob: Job? = null
    private var httpServerSocket: ServerSocket? = null
    private var nmeaServerSocket: ServerSocket? = null

    private val activeNmeaClients = ConcurrentHashMap.newKeySet<PrintWriter>()

    fun startServer(context: Context, httpPort: Int = DEFAULT_HTTP_PORT, nmeaPort: Int = DEFAULT_NMEA_PORT) {
        if (_isServerRunning.value) return

        val detectedIp = getHotspotOrWifiIpAddress(context)
        _serverUrl.value = "http://$detectedIp:$httpPort"
        Log.i(TAG, "Starting Hotspot Location Server at ${_serverUrl.value} and NMEA TCP :$nmeaPort")

        serverJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            _isServerRunning.value = true

            // Launch HTTP Server
            launch { runHttpServer(httpPort, detectedIp) }

            // Launch NMEA TCP Server
            launch { runNmeaTcpServer(nmeaPort) }

            // Launch NMEA Periodic Broadcast Loop (1 Hz)
            launch { runNmeaBroadcastLoop() }
        }
    }

    fun stopServer() {
        Log.i(TAG, "Stopping Hotspot Location Server...")
        _isServerRunning.value = false
        _connectedClientsCount.value = 0
        try {
            httpServerSocket?.close()
        } catch (ignored: Exception) {}
        try {
            nmeaServerSocket?.close()
        } catch (ignored: Exception) {}
        activeNmeaClients.clear()
        serverJob?.cancel()
        serverJob = null
    }

    fun updateLocation(lat: Double, lon: Double, alt: Double = 15.0, speedMps: Float = 0.0f, bearingDeg: Float = 0.0f) {
        currentLat = lat
        currentLon = lon
        currentAlt = alt
        currentSpeedMps = speedMps
        currentBearingDeg = bearingDeg
        lastUpdateTimestamp = System.currentTimeMillis()
    }

    private suspend fun runHttpServer(port: Int, hostIp: String) = withContext(Dispatchers.IO) {
        try {
            httpServerSocket = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0")).apply {
                reuseAddress = true
            }
            Log.i(TAG, "HTTP Server listening on 0.0.0.0:$port")

            while (isActive && _isServerRunning.value) {
                try {
                    val clientSocket = httpServerSocket?.accept() ?: break
                    launch {
                        handleHttpClient(clientSocket, hostIp, port)
                    }
                } catch (e: SocketException) {
                    break // Server socket closed
                } catch (e: Exception) {
                    Log.w(TAG, "HTTP accept error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP Server on port $port: ${e.message}", e)
        }
    }

    private suspend fun handleHttpClient(socket: Socket, hostIp: String, port: Int) = withContext(Dispatchers.IO) {
        try {
            socket.soTimeout = 10000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val outputStream = BufferedOutputStream(socket.getOutputStream())

            val requestLine = reader.readLine() ?: return@withContext
            val parts = requestLine.split(" ")
            if (parts.size < 2) return@withContext

            val method = parts[0]
            val path = parts[1].split("?")[0]

            when {
                path == "/location.json" || path == "/api/location" -> {
                    serveJsonLocation(outputStream)
                }
                path == "/nmea" -> {
                    serveNmeaStream(socket, outputStream)
                    return@withContext // Stream keeps connection open
                }
                path == "/gps.gpx" -> {
                    serveGpx(outputStream)
                }
                path == "/override.js" -> {
                    serveGeolocationOverrideJs(outputStream, hostIp, port)
                }
                else -> {
                    serveWebDashboard(outputStream, hostIp, port)
                }
            }
            outputStream.flush()
        } catch (e: Exception) {
            // Client disconnect
        } finally {
            try {
                socket.close()
            } catch (ignored: Exception) {}
        }
    }

    private fun serveJsonLocation(out: OutputStream) {
        val json = JSONObject().apply {
            put("status", "ACTIVE")
            put("latitude", currentLat)
            put("longitude", currentLon)
            put("altitude", currentAlt)
            put("speedKmh", currentSpeedMps * 3.6)
            put("speedMps", currentSpeedMps)
            put("bearing", currentBearingDeg)
            put("accuracy", 1.0)
            put("satellites", 24)
            put("timestamp", lastUpdateTimestamp)
            put("provider", "nowhere_hotspot_gps")
        }.toString()

        val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Content-Length: ${json.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n" + json

        out.write(response.toByteArray(Charsets.UTF_8))
    }

    private fun serveGpx(out: OutputStream) {
        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(lastUpdateTimestamp))

        val gpx = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Nowhere Hotspot GPS" xmlns="http://www.topografix.com/GPX/1/1">
  <wpt lat="$currentLat" lon="$currentLon">
    <ele>$currentAlt</ele>
    <time>$nowIso</time>
    <name>Nowhere Mock GPS</name>
    <sym>Waypoint</sym>
  </wpt>
</gpx>""".trimIndent()

        val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/gpx+xml; charset=UTF-8\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Content-Disposition: attachment; filename=\"nowhere_location.gpx\"\r\n" +
                "Content-Length: ${gpx.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n" + gpx

        out.write(response.toByteArray(Charsets.UTF_8))
    }

    private fun serveGeolocationOverrideJs(out: OutputStream, hostIp: String, port: Int) {
        val js = """
// Nowhere Hotspot GPS Injector for Connected Browsers
(function() {
  const SERVER_URL = 'http://$hostIp:$port/location.json';
  console.log('[Nowhere GPS] Injecting mock location from ' + SERVER_URL);

  let cachedPosition = {
    coords: {
      latitude: $currentLat,
      longitude: $currentLon,
      altitude: $currentAlt,
      accuracy: 1.0,
      altitudeAccuracy: 0.5,
      heading: $currentBearingDeg,
      speed: $currentSpeedMps
    },
    timestamp: Date.now()
  };

  async function pollLocation() {
    try {
      const res = await fetch(SERVER_URL);
      const data = await res.json();
      if (data && data.latitude !== undefined) {
        cachedPosition = {
          coords: {
            latitude: data.latitude,
            longitude: data.longitude,
            altitude: data.altitude || 15.0,
            accuracy: data.accuracy || 1.0,
            altitudeAccuracy: 0.5,
            heading: data.bearing || 0.0,
            speed: data.speedMps || 0.0
          },
          timestamp: data.timestamp || Date.now()
        };
      }
    } catch(e) {}
  }
  setInterval(pollLocation, 1000);
  pollLocation();

  if (navigator.geolocation) {
    navigator.geolocation.getCurrentPosition = function(success, error, options) {
      setTimeout(() => success(cachedPosition), 50);
    };
    navigator.geolocation.watchPosition = function(success, error, options) {
      const id = setInterval(() => success(cachedPosition), 1000);
      return id;
    };
    navigator.geolocation.clearWatch = function(id) {
      clearInterval(id);
    };
  }
  window.__nowhereGpsActive = true;
  console.log('✅ Nowhere GPS Override Active! Spoofed to: ' + cachedPosition.coords.latitude + ', ' + cachedPosition.coords.longitude);
})();
""".trimIndent()

        val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/javascript; charset=UTF-8\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Content-Length: ${js.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n" + js

        out.write(response.toByteArray(Charsets.UTF_8))
    }

    private fun serveWebDashboard(out: OutputStream, hostIp: String, port: Int) {
        val html = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Nowhere Hotspot GPS Radar</title>
  <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
  <style>
    :root {
      --bg: #0B0E14;
      --card-bg: #141923;
      --border: #232B3E;
      --primary: #FF3B30;
      --accent: #387BFF;
      --green: #34C759;
      --text: #F2F5F8;
      --muted: #8E9BAE;
    }
    * { margin:0; padding:0; box-sizing:border-box; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
    body { background: var(--bg); color: var(--text); padding: 20px; display: flex; flex-direction: column; align-items: center; min-height: 100vh; }
    .container { max-width: 720px; width: 100%; display: flex; flex-direction: column; gap: 16px; }
    .header { display: flex; align-items: center; justify-content: space-between; padding: 16px 20px; background: var(--card-bg); border: 1px solid var(--border); border-radius: 16px; }
    .logo-area { display: flex; align-items: center; gap: 12px; }
    .status-badge { display: flex; align-items: center; gap: 6px; padding: 6px 14px; background: rgba(52, 199, 89, 0.15); border: 1px solid rgba(52, 199, 89, 0.4); border-radius: 20px; color: var(--green); font-size: 12px; font-weight: 700; }
    .pulse-dot { width: 8px; height: 8px; background: var(--green); border-radius: 50%; animation: pulse 1.5s infinite; }
    @keyframes pulse { 0% { opacity: 1; transform: scale(1); } 50% { opacity: 0.4; transform: scale(1.3); } 100% { opacity: 1; transform: scale(1); } }
    .stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 12px; }
    .stat-card { background: var(--card-bg); border: 1px solid var(--border); border-radius: 14px; padding: 14px; display: flex; flex-direction: column; gap: 4px; }
    .stat-label { font-size: 11px; text-transform: uppercase; color: var(--muted); letter-spacing: 0.05em; font-weight: 600; }
    .stat-val { font-size: 18px; font-weight: 800; color: var(--text); font-family: monospace; }
    #map { height: 320px; width: 100%; border-radius: 16px; border: 1px solid var(--border); z-index: 1; }
    .code-card { background: var(--card-bg); border: 1px solid var(--border); border-radius: 16px; padding: 18px; display: flex; flex-direction: column; gap: 12px; }
    .btn { background: var(--primary); color: white; border: none; padding: 12px 18px; border-radius: 10px; font-weight: 700; cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 8px; transition: 0.2s; }
    .btn:hover { opacity: 0.9; transform: translateY(-1px); }
    .btn-secondary { background: #232B3E; color: var(--text); }
    .endpoints { display: flex; flex-wrap: wrap; gap: 8px; }
    .endpoint-pill { background: rgba(56, 123, 255, 0.12); border: 1px solid rgba(56, 123, 255, 0.3); padding: 6px 12px; border-radius: 8px; color: var(--accent); font-size: 12px; font-family: monospace; text-decoration: none; }
  </style>
</head>
<body>
  <div class="container">
    <div class="header">
      <div class="logo-area">
        <h2 style="font-size: 20px; font-weight: 800;">🛰️ Nowhere Hotspot GPS</h2>
      </div>
      <div class="status-badge">
        <div class="pulse-dot"></div>
        LIVE SYNC ACTIVE
      </div>
    </div>

    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-label">Latitude</div>
        <div class="stat-val" id="valLat">$currentLat</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">Longitude</div>
        <div class="stat-val" id="valLon">$currentLon</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">Altitude</div>
        <div class="stat-val" id="valAlt">$currentAlt m</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">Speed</div>
        <div class="stat-val" id="valSpeed">${String.format("%.1f", currentSpeedMps * 3.6f)} km/h</div>
      </div>
    </div>

    <div id="map"></div>

    <div class="code-card">
      <h3 style="font-size: 15px; font-weight: 700;">🌐 Connected Device Location Endpoints</h3>
      <div class="endpoints">
        <a class="endpoint-pill" href="/location.json" target="_blank">📄 /location.json (REST API)</a>
        <a class="endpoint-pill" href="/nmea" target="_blank">📡 /nmea (NMEA 0183 Stream)</a>
        <a class="endpoint-pill" href="/gps.gpx" target="_blank">🗺️ /gps.gpx (GPX Waypoint)</a>
        <a class="endpoint-pill" href="/override.js" target="_blank">⚙️ /override.js (Browser Polyfill)</a>
      </div>
      <button class="btn" onclick="copyBookmarklet()">📋 Copy Browser Geolocation Override Script</button>
    </div>
  </div>

  <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
  <script>
    const map = L.map('map').setView([$currentLat, $currentLon], 15);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19 }).addTo(map);
    const marker = L.marker([$currentLat, $currentLon]).addTo(map);

    async function refresh() {
      try {
        const res = await fetch('/location.json');
        const d = await res.json();
        document.getElementById('valLat').innerText = Number(d.latitude).toFixed(6);
        document.getElementById('valLon').innerText = Number(d.longitude).toFixed(6);
        document.getElementById('valAlt').innerText = Number(d.altitude).toFixed(1) + ' m';
        document.getElementById('valSpeed').innerText = Number(d.speedKmh).toFixed(1) + ' km/h';
        const newPos = [d.latitude, d.longitude];
        marker.setLatLng(newPos);
      } catch(e) {}
    }
    setInterval(refresh, 1000);

    function copyBookmarklet() {
      const script = "fetch('http://$hostIp:$port/override.js').then(r=>r.text()).then(eval);";
      navigator.clipboard.writeText(script).then(() => {
        alert('✅ Geolocation script copied! Open DevTools Console on any website (e.g. Google Maps or browser app) on your laptop/iPad and paste it to instantly spoof your browser location.');
      });
    }
  </script>
</body>
</html>
""".trimIndent()

        val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Content-Length: ${html.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n" + html

        out.write(response.toByteArray(Charsets.UTF_8))
    }

    private suspend fun serveNmeaStream(socket: Socket, out: OutputStream) = withContext(Dispatchers.IO) {
        val writer = PrintWriter(OutputStreamWriter(out, Charsets.US_ASCII), true)
        activeNmeaClients.add(writer)
        _connectedClientsCount.value = activeNmeaClients.size

        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: keep-alive\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.flush()

        try {
            while (isActive && _isServerRunning.value && !socket.isClosed) {
                delay(1000L)
                val sentences = generateNmea0183Sentences(currentLat, currentLon, currentAlt, currentSpeedMps, currentBearingDeg)
                for (s in sentences) {
                    writer.println("data: $s\n")
                }
                out.flush()
            }
        } catch (e: Exception) {
            // Disconnected
        } finally {
            activeNmeaClients.remove(writer)
            _connectedClientsCount.value = activeNmeaClients.size
        }
    }

    private suspend fun runNmeaTcpServer(port: Int) = withContext(Dispatchers.IO) {
        try {
            nmeaServerSocket = ServerSocket(port, 20, InetAddress.getByName("0.0.0.0")).apply {
                reuseAddress = true
            }
            Log.i(TAG, "NMEA TCP Server listening on 0.0.0.0:$port")

            while (isActive && _isServerRunning.value) {
                try {
                    val clientSocket = nmeaServerSocket?.accept() ?: break
                    launch {
                        handleNmeaTcpClient(clientSocket)
                    }
                } catch (e: SocketException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "NMEA TCP accept error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NMEA TCP Server on port $port: ${e.message}")
        }
    }

    private suspend fun handleNmeaTcpClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.US_ASCII), true)
            activeNmeaClients.add(writer)
            _connectedClientsCount.value = activeNmeaClients.size

            while (isActive && _isServerRunning.value && !socket.isClosed) {
                delay(1000L)
                val sentences = generateNmea0183Sentences(currentLat, currentLon, currentAlt, currentSpeedMps, currentBearingDeg)
                for (s in sentences) {
                    writer.println(s)
                }
            }
        } catch (e: Exception) {
            // Disconnected
        } finally {
            try {
                socket.close()
            } catch (ignored: Exception) {}
            _connectedClientsCount.value = activeNmeaClients.size
        }
    }

    private suspend fun runNmeaBroadcastLoop() = withContext(Dispatchers.IO) {
        while (isActive && _isServerRunning.value) {
            delay(1000L)
            if (activeNmeaClients.isNotEmpty()) {
                val sentences = generateNmea0183Sentences(currentLat, currentLon, currentAlt, currentSpeedMps, currentBearingDeg)
                for (client in activeNmeaClients) {
                    try {
                        for (s in sentences) {
                            client.println(s)
                        }
                    } catch (e: Exception) {
                        activeNmeaClients.remove(client)
                    }
                }
                _connectedClientsCount.value = activeNmeaClients.size
            }
        }
    }

    /**
     * Generates standard NMEA 0183 Sentences ($GPRMC, $GPGGA, $GPVTG) compliant with all navigation & GPS clients.
     */
    fun generateNmea0183Sentences(lat: Double, lon: Double, alt: Double, speedMps: Float, bearingDeg: Float): List<String> {
        val utcTime = SimpleDateFormat("HHmmss.SS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val utcDate = SimpleDateFormat("ddMMyy", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val latDeg = Math.abs(lat.toInt())
        val latMin = (Math.abs(lat) - latDeg) * 60.0
        val latStr = String.format(Locale.US, "%02d%07.4f", latDeg, latMin)
        val latDir = if (lat >= 0) "N" else "S"

        val lonDeg = Math.abs(lon.toInt())
        val lonMin = (Math.abs(lon) - lonDeg) * 60.0
        val lonStr = String.format(Locale.US, "%03d%07.4f", lonDeg, lonMin)
        val lonDir = if (lon >= 0) "E" else "W"

        val speedKnots = speedMps * 1.943844f
        val speedKmh = speedMps * 3.6f

        // 1. $GPRMC - Recommended Minimum Specific GPS Data
        val rmcRaw = String.format(
            Locale.US,
            "GPRMC,%s,A,%s,%s,%s,%s,%.2f,%.2f,%s,,,A",
            utcTime, latStr, latDir, lonStr, lonDir, speedKnots, bearingDeg, utcDate
        )
        val rmc = formatWithChecksum(rmcRaw)

        // 2. $GPGGA - Global Positioning System Fix Data
        val ggaRaw = String.format(
            Locale.US,
            "GPGGA,%s,%s,%s,%s,%s,1,12,0.8,%.1f,M,0.0,M,,",
            utcTime, latStr, latDir, lonStr, lonDir, alt
        )
        val gga = formatWithChecksum(ggaRaw)

        // 3. $GPVTG - Track Made Good and Ground Speed
        val vtgRaw = String.format(
            Locale.US,
            "GPVTG,%.2f,T,,M,%.2f,N,%.2f,K,A",
            bearingDeg, speedKnots, speedKmh
        )
        val vtg = formatWithChecksum(vtgRaw)

        return listOf(rmc, gga, vtg)
    }

    private fun formatWithChecksum(sentenceWithoutDollarOrStar: String): String {
        var checksum = 0
        for (char in sentenceWithoutDollarOrStar) {
            checksum = checksum xor char.code
        }
        val hexChecksum = String.format(Locale.US, "%02X", checksum)
        return "\$$sentenceWithoutDollarOrStar*$hexChecksum"
    }

    /**
     * Resolves the device's Hotspot gateway IP (192.168.43.1 / 192.168.44.1) or active Wi-Fi IP address.
     */
    fun getHotspotOrWifiIpAddress(context: Context): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var fallbackIp: String? = null

            for (networkInterface in Collections.list(interfaces)) {
                val name = networkInterface.name.lowercase()
                val isHotspotInterface = name.startsWith("ap") || name.startsWith("wlan1") || name.startsWith("swlan") || name.startsWith("rndis") || name.startsWith("tether")

                for (inetAddress in Collections.list(networkInterface.inetAddresses)) {
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        val hostAddress = inetAddress.hostAddress ?: continue
                        if (isHotspotInterface || hostAddress.startsWith("192.168.43.") || hostAddress.startsWith("192.168.44.")) {
                            return hostAddress
                        }
                        if (fallbackIp == null && !hostAddress.startsWith("127.")) {
                            fallbackIp = hostAddress
                        }
                    }
                }
            }
            if (fallbackIp != null) return fallbackIp
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving network interfaces: ${e.message}")
        }

        // Standard Android default hotspot gateway IP
        return "192.168.43.1"
    }
}
