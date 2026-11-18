package com.streamify.app.data.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.streamify.app.util.SLog as Log
import com.streamify.app.data.supabase.SupabaseClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.*
import java.util.Collections
import java.util.UUID

data class MeshPeer(
    val deviceId: String,
    val deviceName: String,
    val ipAddress: InetAddress,
    val port: Int = MeshDiscoveryEngine.MESH_PORT,
    val isLocalLan: Boolean = true,
    val lastSeenMs: Long = System.currentTimeMillis()
)

class MeshDiscoveryEngine private constructor(private val context: Context) {

    companion object {
        private const val TAG = "MeshDiscoveryEngine"
        const val MESH_PORT = 7777
        const val SERVICE_TYPE = "_streamify._udp."

        @Volatile
        private var INSTANCE: MeshDiscoveryEngine? = null

        fun getInstance(context: Context): MeshDiscoveryEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MeshDiscoveryEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var nsdManager: NsdManager? = null
    private var udpSocket: DatagramSocket? = null
    private var listenerJob: Job? = null
    private var beaconJob: Job? = null
    private var isRunning = false

    val deviceId: String = UUID.randomUUID().toString().take(8)
    val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private val _discoveredPeers = MutableStateFlow<Map<String, MeshPeer>>(emptyMap())
    val discoveredPeers: StateFlow<Map<String, MeshPeer>> = _discoveredPeers.asStateFlow()

    private val _peerEvents = MutableSharedFlow<MeshPeer>(extraBufferCapacity = 16)
    val peerEvents: SharedFlow<MeshPeer> = _peerEvents.asSharedFlow()

    private val _rawUdpPackets = MutableSharedFlow<DatagramPacket>(extraBufferCapacity = 64)
    val rawUdpPackets: SharedFlow<DatagramPacket> = _rawUdpPackets.asSharedFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun startDiscovery(userId: String) {
        if (isRunning) return
        isRunning = true

        startUdpSocket()
        startLanBeacon(userId)
        startMdnsService()
        startCloudSignaling(userId)
    }

    private fun startUdpSocket() {
        try {
            udpSocket?.close()
            udpSocket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(MESH_PORT))
                soTimeout = 0 // Blocking read on dedicated IO coroutine
            }

            listenerJob = scope.launch {
                val buffer = ByteArray(2048)
                while (isActive && isRunning) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        udpSocket?.receive(packet)

                        // 1. Check if packet is a PTP binary timestamp (length 16 or 24)
                        if (packet.length == 16 || packet.length == 24) {
                            _rawUdpPackets.emit(DatagramPacket(packet.data.copyOf(packet.length), packet.length, packet.address, packet.port))
                        } else {
                            // 2. Parse JSON beacon / presence message
                            val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            handleBeaconJson(message, packet.address, packet.port)
                        }
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind UDP mesh socket on port $MESH_PORT", e)
        }
    }

    private fun startLanBeacon(userId: String) {
        beaconJob?.cancel()
        beaconJob = scope.launch {
            while (isActive && isRunning) {
                try {
                    val beaconJson = JSONObject().apply {
                        put("type", "BEACON")
                        put("user_id", userId)
                        put("device_id", deviceId)
                        put("device_name", deviceName)
                        put("port", MESH_PORT)
                        put("timestamp", System.currentTimeMillis())
                    }.toString()

                    val bytes = beaconJson.toByteArray(Charsets.UTF_8)
                    val broadcastAddr = InetAddress.getByName("255.255.255.255")
                    val packet = DatagramPacket(bytes, bytes.size, broadcastAddr, MESH_PORT)

                    udpSocket?.send(packet)
                } catch (e: Exception) {
                    // Ignore broadcast send errors (e.g. WiFi interface changing)
                }
                delay(3000) // Broadcast beacon every 3s
            }
        }
    }

    private fun handleBeaconJson(jsonStr: String, address: InetAddress, port: Int) {
        try {
            val root = JSONObject(jsonStr)
            val type = root.optString("type", "")
            if (type == "BEACON") {
                val remoteDeviceId = root.optString("device_id", "")
                if (remoteDeviceId.isNotBlank() && remoteDeviceId != deviceId) {
                    val remoteDeviceName = root.optString("device_name", "Streamify Peer")
                    val peer = MeshPeer(
                        deviceId = remoteDeviceId,
                        deviceName = remoteDeviceName,
                        ipAddress = address,
                        port = port,
                        isLocalLan = true,
                        lastSeenMs = System.currentTimeMillis()
                    )

                    val current = _discoveredPeers.value.toMutableMap()
                    current[remoteDeviceId] = peer
                    _discoveredPeers.value = current
                    scope.launch { _peerEvents.emit(peer) }
                }
            }
        } catch (e: Exception) {
            // Not a JSON beacon
        }
    }

    private fun startMdnsService() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "StreamifyMesh_$deviceId"
                serviceType = SERVICE_TYPE
                port = MESH_PORT
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "mDNS Service registered: ${serviceInfo.serviceName}")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS registration failed: $errorCode")
                }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)

            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}
                override fun onServiceFound(service: NsdServiceInfo) {
                    if (service.serviceType.contains("_streamify") && !service.serviceName.contains(deviceId)) {
                        nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                val host = serviceInfo.host ?: return
                                val p = MeshPeer(
                                    deviceId = serviceInfo.serviceName.removePrefix("StreamifyMesh_"),
                                    deviceName = serviceInfo.serviceName,
                                    ipAddress = host,
                                    port = serviceInfo.port,
                                    isLocalLan = true
                                )
                                val current = _discoveredPeers.value.toMutableMap()
                                current[p.deviceId] = p
                                _discoveredPeers.value = current
                                scope.launch { _peerEvents.emit(p) }
                            }
                        })
                    }
                }
                override fun onServiceLost(service: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }

            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS init warning", e)
        }
    }

    private fun startCloudSignaling(userId: String) {
        scope.launch {
            while (isActive && isRunning) {
                try {
                    // Update user's active cloud presence with local IP
                    val localIp = getLocalIpAddress()
                    if (localIp != null) {
                        // Keep presence active in friend_activity / profiles table
                    }
                } catch (e: Exception) {
                    // Ignore cloud signaling errors
                }
                delay(10000)
            }
        }
    }

    fun sendUdpPacket(data: ByteArray, targetIp: InetAddress, targetPort: Int = MESH_PORT) {
        scope.launch {
            try {
                val packet = DatagramPacket(data, data.size, targetIp, targetPort)
                udpSocket?.send(packet)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send UDP packet to $targetIp:$targetPort", e)
            }
        }
    }

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.st("MeshDiscoveryEngine", "MeshDiscoveryEngine.getLocalIpAddress failed", e)
        }
        return null
    }

    fun stopDiscovery() {
        isRunning = false
        listenerJob?.cancel()
        beaconJob?.cancel()
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
            udpSocket?.close()
        } catch (e: Exception) {
            // Ignore teardown errors
        }
    }
}
