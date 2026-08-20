package com.fakegps.mocklocation.hotspot

import android.content.Context
import android.net.wifi.WifiManager
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
 * Hotspot Location & GPS Tethering Server (BETA).
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

    private val _allAvailableUrls = MutableStateFlow<List<String>>(listOf("http://192.168.43.1:8088"))
    val allAvailableUrls: StateFlow<List<String>> = _allAvailableUrls.asStateFlow()

    private var serverJob: Job? = null
    private var httpServerSocket: ServerSocket? = null
    private var nmeaServerSocket: ServerSocket? = null

    private val activeNmeaClients = ConcurrentHashMap.newKeySet<PrintWriter>()

    fun startServer(context: Context, httpPort: Int = DEFAULT_HTTP_PORT, nmeaPort: Int = DEFAULT_NMEA_PORT) {
        if (_isServerRunning.value) {
            refreshIpAddress(context, httpPort)
            return
        }

        val detectedIp = getHotspotOrWifiIpAddress(context)
        _serverUrl.value = "http://$detectedIp:$httpPort"
        updateAllUrls(context, httpPort)
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

    fun refreshIpAddress(context: Context, httpPort: Int = DEFAULT_HTTP_PORT): String {
        val detectedIp = getHotspotOrWifiIpAddress(context)
        _serverUrl.value = "http://$detectedIp:$httpPort"
        updateAllUrls(context, httpPort)
        return detectedIp
    }

    private fun updateAllUrls(context: Context, httpPort: Int = DEFAULT_HTTP_PORT) {
        val ips = getAllLocalIpAddresses(context)
        if (ips.isNotEmpty()) {
            _allAvailableUrls.value = ips.map { "http://$it:$httpPort" }
        } else {
            _allAvailableUrls.value = listOf("http://192.168.43.1:$httpPort")
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
            httpServerSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", port), 50)
            }
            Log.i(TAG, "HTTP Server successfully listening on 0.0.0.0:$port")

            while (isActive && _isServerRunning.value) {
                try {
                    val clientSocket = httpServerSocket?.accept() ?: break
                    launch(Dispatchers.IO) {
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
            socket.soTimeout = 8000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val outputStream = BufferedOutputStream(socket.getOutputStream())

            val requestLine = reader.readLine() ?: return@withContext
            val parts = requestLine.split(" ")
            if (parts.size < 2) return@withContext

            val method = parts[0]
            val path = parts[1].split("?")[0]

            // Fully consume remaining HTTP request headers before responding
            var headerLine: String? = reader.readLine()
            while (headerLine != null && headerLine.isNotEmpty()) {
                headerLine = reader.readLine()
            }

            if (method.equals("OPTIONS", ignoreCase = true)) {
                serveCorsPreflight(outputStream)
                outputStream.flush()
                return@withContext
            }

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
                path == "/ping" || path == "/health" -> {
                    serveHealthCheck(outputStream)
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

    private fun serveCorsPreflight(out: OutputStream) {
        val response = "HTTP/1.1 204 No Content\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: *\r\n" +
                "Access-Control-Max-Age: 86400\r\n" +
                "Connection: close\r\n\r\n"
        out.write(response.toByteArray(Charsets.UTF_8))
    }

    private fun serveHealthCheck(out: OutputStream) {
        val json = JSONObject().apply {
            put("status", "ONLINE")
            put("service", "Nowhere GPS Hotspot Server")
            put("version", "BETA")
            put("timestamp", System.currentTimeMillis())
        }.toString()

        val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Content-Length: ${json.toByteArray(Charsets.UTF_8).size}\r\n" +
                "Connection: close\r\n\r\n" + json

        out.write(response.toByteArray(Charsets.UTF_8))
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
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: *\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Content-Length: ${json.toByteArray(Charsets.UTF_8).size}\r\n" +
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
                "Content-Length: ${gpx.toByteArray(Charsets.UTF_8).size}\r\n" +
                "Connection: close\r\n\r\n" + gpx

        out.write(response.toByteArray(Charsets.UTF_8))
    }

    private fun serveGeolocationOverrideJs(out: OutputStream, hostIp: String, port: Int) {
        val js = """
// Nowhere Hotspot GPS Injector for Connected Browsers (BETA)
(function() {
  const SERVER_URL = 'http://' + window.location.hostname + ':' + (window.location.port || '$port') + '/location.json';
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
            latitude: Number(data.latitude),
            longitude: Number(data.longitude),
            altitude: Number(data.altitude) || 15.0,
            accuracy: Number(data.accuracy) || 1.0,
            altitudeAccuracy: 0.5,
            heading: Number(data.bearing) || 0.0,
            speed: Number(data.speedMps) || 0.0
          },
          timestamp: data.timestamp || Date.now()
        };
      }
    } catch(e) {}
  }
  setInterval(pollLocation, 500);
  pollLocation();

  if (navigator.geolocation) {
    navigator.geolocation.getCurrentPosition = function(success, error, options) {
      setTimeout(() => success(cachedPosition), 20);
    };
    navigator.geolocation.watchPosition = function(success, error, options) {
      const id = setInterval(() => success(cachedPosition), 500);
      return id;
    };
    navigator.geolocation.clearWatch = function(id) {
      clearInterval(id);
    };
  }
  window.__nowhereGpsActive = true;
  console.log('✅ Nowhere GPS Active! Coordinates: ' + cachedPosition.coords.latitude + ', ' + cachedPosition.coords.longitude);
})();
""".trimIndent()

        val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/javascript; charset=UTF-8\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Content-Length: ${js.toByteArray(Charsets.UTF_8).size}\r\n" +
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
  <title>Nowhere GPS Hotspot Radar (BETA)</title>
  <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
  <style>
    :root {
      --bg: #090B0E;
      --card-bg: #12151E;
      --card-elevated: #1A1E2C;
      --border: #2A1818;
      --border-red: rgba(255, 59, 48, 0.35);
      --primary-red: #FF3B30;
      --primary-red-hover: #E52E24;
      --glow-red: rgba(255, 59, 48, 0.4);
      --green: #34C759;
      --text: #FFFFFF;
      --muted: #A0AAB8;
      --beta-gold: #FF9500;
    }
    * { margin:0; padding:0; box-sizing:border-box; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
    body { background: var(--bg); color: var(--text); padding: 16px; display: flex; flex-direction: column; align-items: center; min-height: 100vh; }
    .container { max-width: 720px; width: 100%; display: flex; flex-direction: column; gap: 14px; }
    
    /* Red Header with Nowhere App Logo & BETA Tag */
    .header { display: flex; align-items: center; justify-content: space-between; padding: 16px 20px; background: var(--card-bg); border: 1px solid var(--border-red); border-radius: 16px; box-shadow: 0 4px 20px rgba(255, 59, 48, 0.1); }
    .logo-area { display: flex; align-items: center; gap: 12px; }
    .logo-container { width: 42px; height: 42px; background: #181B26; border: 1px solid rgba(255,59,48,0.3); border-radius: 12px; display: flex; align-items: center; justify-content: center; box-shadow: 0 0 14px var(--glow-red); }
    
    .title-row { display: flex; align-items: center; gap: 8px; }
    .beta-badge { background: rgba(255, 149, 0, 0.15); border: 1px solid var(--beta-gold); color: var(--beta-gold); font-size: 10px; font-weight: 900; letter-spacing: 0.08em; padding: 2px 7px; border-radius: 6px; }
    
    .status-badge { display: flex; align-items: center; gap: 8px; padding: 6px 14px; background: rgba(255, 59, 48, 0.15); border: 1px solid var(--primary-red); border-radius: 20px; color: #FF6961; font-size: 12px; font-weight: 800; letter-spacing: 0.05em; }
    .pulse-dot { width: 9px; height: 9px; background: var(--primary-red); border-radius: 50%; box-shadow: 0 0 8px var(--primary-red); animation: pulse 1.2s infinite; }
    @keyframes pulse { 0% { opacity: 1; transform: scale(1); } 50% { opacity: 0.3; transform: scale(1.4); } 100% { opacity: 1; transform: scale(1); } }
    
    /* Stats Grid */
    .stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 10px; }
    .stat-card { background: var(--card-bg); border: 1px solid rgba(255, 255, 255, 0.08); border-radius: 14px; padding: 14px; display: flex; flex-direction: column; gap: 4px; border-left: 3px solid var(--primary-red); }
    .stat-label { font-size: 11px; text-transform: uppercase; color: var(--muted); letter-spacing: 0.06em; font-weight: 700; }
    .stat-val { font-size: 18px; font-weight: 800; color: var(--text); font-family: monospace; }
    
    /* Map & Fallback Radar Canvas */
    .map-container { position: relative; width: 100%; height: 320px; border-radius: 16px; border: 1px solid var(--border-red); overflow: hidden; background: #0B0E14; box-shadow: 0 6px 24px rgba(0,0,0,0.5); }
    #map { height: 100%; width: 100%; z-index: 1; }
    #radarCanvas { position: absolute; top:0; left:0; width:100%; height:100%; display:none; z-index: 0; }
    
    /* Quick Action Cards */
    .card { background: var(--card-bg); border: 1px solid rgba(255, 255, 255, 0.08); border-radius: 16px; padding: 18px; display: flex; flex-direction: column; gap: 12px; }
    .card-title { font-size: 15px; font-weight: 800; display: flex; align-items: center; gap: 8px; color: var(--text); }
    
    .actions-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 10px; }
    .btn-red { background: linear-gradient(135deg, #FF3B30 0%, #D32F2F 100%); color: white; border: none; padding: 14px 16px; border-radius: 12px; font-weight: 800; cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 8px; transition: all 0.2s; font-size: 13px; text-decoration: none; box-shadow: 0 4px 14px var(--glow-red); }
    .btn-red:hover { background: linear-gradient(135deg, #FF5247 0%, #E53935 100%); transform: translateY(-2px); box-shadow: 0 6px 20px var(--glow-red); }
    .btn-secondary { background: var(--card-elevated); color: var(--text); border: 1px solid rgba(255,255,255,0.12); padding: 14px 16px; border-radius: 12px; font-weight: 700; cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 8px; font-size: 13px; text-decoration: none; }
    .btn-secondary:hover { background: #262B3D; }

    /* Red Easy Steps */
    .easy-step { display: flex; align-items: flex-start; gap: 12px; background: var(--card-elevated); padding: 14px; border-radius: 12px; border-left: 3px solid var(--primary-red); }
    .step-circle { width: 26px; height: 26px; background: var(--primary-red); color: white; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-weight: 900; font-size: 13px; flex-shrink: 0; }
    .step-text { font-size: 13px; line-height: 1.5; color: var(--muted); }
    .step-text strong { color: var(--text); font-weight: 700; }
    
    /* Footer */
    .footer { text-align: center; color: var(--muted); font-size: 11px; margin-top: 10px; }
  </style>
</head>
<body>
  <div class="container">
    
    <!-- Red Header with Nowhere Vector Logo & BETA Tag -->
    <div class="header">
      <div class="logo-area">
        <div class="logo-container">
          <svg width="28" height="28" viewBox="0 0 200 200" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M100,20 C133.14,20 160,46.86 160,80 C160,118 100,185 100,185 C100,185 40,118 40,80 C40,46.86 66.86,20 100,20 Z" fill="#FF3B30"/>
            <path d="M100,20 C66.86,20 40,46.86 40,80 L75,80 C75,66.2 86.2,55 100,55 Z" fill="#991B1B"/>
            <path d="M100,55 C113.8,55 125,66.2 125,80 C125,93.8 113.8,105 100,105 C86.2,105 75,93.8 75,80 C75,66.2 86.2,55 100,55 Z" fill="#181B26"/>
          </svg>
        </div>
        <div>
          <div class="title-row">
            <h2 style="font-size: 18px; font-weight: 900; letter-spacing: -0.02em;">Nowhere GPS Sync</h2>
            <span class="beta-badge">BETA</span>
          </div>
          <p style="font-size: 11px; color: var(--muted); margin-top: 2px;">Hotspot Location Gateway</p>
        </div>
      </div>
      <div class="status-badge">
        <div class="pulse-dot"></div>
        LIVE SYNC
      </div>
    </div>

    <!-- Live Coordinates Grid -->
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
        <div class="stat-val" id="valSpeed">${String.format(Locale.US, "%.1f", currentSpeedMps * 3.6f)} km/h</div>
      </div>
    </div>

    <!-- Map & Canvas Container -->
    <div class="map-container">
      <div id="map"></div>
      <canvas id="radarCanvas"></canvas>
    </div>

    <!-- 1-Tap Quick Launchers -->
    <div class="card">
      <div class="card-title">🚀 1-Tap Location Launchers</div>
      <div class="actions-grid">
        <a id="btnGoogleMaps" class="btn-red" href="https://www.google.com/maps?q=$currentLat,$currentLon" target="_blank">
          🗺️ Open in Google Maps
        </a>
        <a id="btnAppleMaps" class="btn-secondary" href="http://maps.apple.com/?q=$currentLat,$currentLon&ll=$currentLat,$currentLon" target="_blank">
          🍎 Open in Apple Maps
        </a>
        <button class="btn-secondary" onclick="syncBrowserTab()">
          ⚡ Sync This Browser Tab
        </button>
        <a class="btn-secondary" href="/gps.gpx" download="nowhere_location.gpx">
          📥 Download GPS File (.gpx)
        </a>
      </div>
    </div>

    <!-- Simple Connection Steps -->
    <div class="card">
      <div class="card-title">📱 How it Works on Your Devices</div>
      
      <div class="easy-step">
        <div class="step-circle">1</div>
        <div class="step-text">
          <strong>Connected to Hotspot:</strong> Your iPad, MacBook, PC, or iPhone is connected to this phone's Wi-Fi.
        </div>
      </div>

      <div class="easy-step">
        <div class="step-circle">2</div>
        <div class="step-text">
          <strong>Instant Map Sync:</strong> This page automatically tracks your spoofed location in real time. Tap <strong>"Open in Google Maps"</strong> or <strong>"Open in Apple Maps"</strong> to navigate anywhere instantly.
        </div>
      </div>

      <div class="easy-step">
        <div class="step-circle">3</div>
        <div class="step-text">
          <strong>Browser Geolocation Sync:</strong> Tap <strong>"Sync This Browser Tab"</strong> to inject this spoofed position directly into your web browser.
        </div>
      </div>
    </div>

    <div class="footer">
      Nowhere Mock Location • Hotspot GPS Gateway (BETA)
    </div>

  </div>

  <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
  <script>
    let map = null;
    let marker = null;
    let radarCircle = null;
    let lastLat = $currentLat;
    let lastLon = $currentLon;

    // Fail-safe Leaflet initialization with offline fallback
    try {
      if (typeof L !== 'undefined') {
        map = L.map('map').setView([$currentLat, $currentLon], 15);
        L.tileLayer('https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png', {
          maxZoom: 19,
          attribution: '© OpenStreetMap © CARTO'
        }).addTo(map);

        const radarIcon = L.divIcon({
          className: 'custom-radar-marker',
          html: '<div style="width:20px;height:20px;background:#FF3B30;border:3px solid #FFFFFF;border-radius:50%;box-shadow:0 0 15px #FF3B30;"></div>',
          iconSize: [20, 20],
          iconAnchor: [10, 10]
        });

        marker = L.marker([$currentLat, $currentLon], { icon: radarIcon }).addTo(map);
        radarCircle = L.circle([$currentLat, $currentLon], {
          color: '#FF3B30',
          fillColor: '#FF3B30',
          fillOpacity: 0.15,
          radius: 120
        }).addTo(map);
      } else {
        showRadarFallback();
      }
    } catch(e) {
      showRadarFallback();
    }

    function showRadarFallback() {
      const cvs = document.getElementById('radarCanvas');
      if (cvs) {
        cvs.style.display = 'block';
        const ctx = cvs.getContext('2d');
        let angle = 0;
        function drawRadar() {
          cvs.width = cvs.clientWidth;
          cvs.height = cvs.clientHeight;
          const cx = cvs.width / 2;
          const cy = cvs.height / 2;
          const r = Math.min(cx, cy) - 20;

          ctx.fillStyle = '#090B0E';
          ctx.fillRect(0, 0, cvs.width, cvs.height);

          // Grid circles
          ctx.strokeStyle = 'rgba(255, 59, 48, 0.25)';
          ctx.lineWidth = 1;
          for (let i = 1; i <= 3; i++) {
            ctx.beginPath();
            ctx.arc(cx, cy, (r / 3) * i, 0, Math.PI * 2);
            ctx.stroke();
          }

          // Sweep
          ctx.save();
          ctx.translate(cx, cy);
          ctx.rotate(angle);
          const grad = ctx.createRadialGradient(0, 0, 0, 0, 0, r);
          grad.addColorStop(0, 'rgba(255, 59, 48, 0.4)');
          grad.addColorStop(1, 'rgba(255, 59, 48, 0.0)');
          ctx.fillStyle = grad;
          ctx.beginPath();
          ctx.moveTo(0, 0);
          ctx.arc(0, 0, r, 0, Math.PI / 3);
          ctx.closePath();
          ctx.fill();
          ctx.restore();

          // Center target
          ctx.fillStyle = '#FF3B30';
          ctx.beginPath();
          ctx.arc(cx, cy, 6, 0, Math.PI * 2);
          ctx.fill();

          angle += 0.04;
          requestAnimationFrame(drawRadar);
        }
        drawRadar();
      }
    }

    async function refresh() {
      try {
        const res = await fetch('/location.json');
        const d = await res.json();
        if (d && d.latitude !== undefined) {
          lastLat = Number(d.latitude);
          lastLon = Number(d.longitude);
          document.getElementById('valLat').innerText = lastLat.toFixed(6);
          document.getElementById('valLon').innerText = lastLon.toFixed(6);
          document.getElementById('valAlt').innerText = Number(d.altitude).toFixed(1) + ' m';
          document.getElementById('valSpeed').innerText = Number(d.speedKmh).toFixed(1) + ' km/h';

          if (map && marker && radarCircle) {
            const newPos = [lastLat, lastLon];
            marker.setLatLng(newPos);
            radarCircle.setLatLng(newPos);
          }

          document.getElementById('btnGoogleMaps').href = 'https://www.google.com/maps?q=' + lastLat + ',' + lastLon;
          document.getElementById('btnAppleMaps').href = 'http://maps.apple.com/?q=' + lastLat + ',' + lastLon + '&ll=' + lastLat + ',' + lastLon;
        }
      } catch(e) {}
    }
    setInterval(refresh, 500);

    function syncBrowserTab() {
      fetch('/override.js')
        .then(r => r.text())
        .then(code => {
          eval(code);
          alert('✅ Location synced in this browser tab! Current position: ' + lastLat.toFixed(5) + ', ' + lastLon.toFixed(5));
        })
        .catch(() => {
          alert('✅ Location is active! Use the Open in Google Maps button to view.');
        });
    }
  </script>
</body>
</html>
""".trimIndent()

        val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Content-Length: ${html.toByteArray(Charsets.UTF_8).size}\r\n" +
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
            nmeaServerSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", port), 20)
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
        var writer: PrintWriter? = null
        try {
            writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.US_ASCII), true)
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
            if (writer != null) {
                activeNmeaClients.remove(writer)
            }
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
            val allIps = getAllLocalIpAddresses(context)
            if (allIps.isNotEmpty()) {
                return allIps[0]
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving network interfaces: ${e.message}")
        }

        // Standard Android default hotspot gateway IP
        return "192.168.43.1"
    }

    /**
     * Gets all valid non-loopback IPv4 addresses sorted with hotspot interfaces prioritized.
     */
    fun getAllLocalIpAddresses(context: Context): List<String> {
        val result = mutableListOf<String>()
        try {
            // Check WifiManager for Wi-Fi interface IP
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                val wifiIp = String.format(
                    Locale.US,
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
                if (wifiIp != "0.0.0.0" && !wifiIp.startsWith("127.")) {
                    result.add(wifiIp)
                }
            }

            // Check network interfaces
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result.distinct()

            for (networkInterface in Collections.list(interfaces)) {
                val name = networkInterface.name.lowercase(Locale.US)
                val isHotspot = name.startsWith("ap") || name.startsWith("wlan1") || name.startsWith("swlan") ||
                        name.startsWith("softap") || name.startsWith("rndis") || name.startsWith("tether") ||
                        name.startsWith("p2p") || name.startsWith("wigig")

                for (inetAddress in Collections.list(networkInterface.inetAddresses)) {
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        val hostAddress = inetAddress.hostAddress ?: continue
                        if (hostAddress.startsWith("127.")) continue
                        if (isHotspot || hostAddress.startsWith("192.168.43.") || hostAddress.startsWith("192.168.44.") || hostAddress.startsWith("192.168.49.") || hostAddress.startsWith("192.168.50.")) {
                            result.add(0, hostAddress) // prioritize hotspot IPs
                        } else {
                            result.add(hostAddress)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getAllLocalIpAddresses error: ${e.message}")
        }

        if (!result.any { it.startsWith("192.168.43.") || it.startsWith("192.168.44.") }) {
            result.add(0, "192.168.43.1")
        }

        return result.distinct()
    }
}
