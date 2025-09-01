package com.example.rc_boat_controller_cellular // Change this to your actual package name

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.usb.UsbManager
import android.location.Location
import android.os.*
import android.telephony.TelephonyManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

// --- Main Activity: UI and Service Control ---
class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()
        setContent {
            RCBoatControllerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BoatControllerScreen()
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}

// --- ViewModel: Holds UI state and listens for updates from the Service ---
class BoatViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val serviceUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val newState = UiState(
                    usbStatus = it.getStringExtra("usbStatus") ?: _uiState.value.usbStatus,
                    mqttStatus = it.getStringExtra("mqttStatus") ?: _uiState.value.mqttStatus,
                    webRtcStatus = it.getStringExtra("webRtcStatus") ?: _uiState.value.webRtcStatus,
                    lastCommand = it.getStringExtra("lastCommand") ?: _uiState.value.lastCommand,
                    boatVoltage = it.getStringExtra("boatVoltage") ?: _uiState.value.boatVoltage,
                    boatTacho = it.getStringExtra("boatTacho") ?: _uiState.value.boatTacho,
                    phoneBattery = it.getStringExtra("phoneBattery") ?: _uiState.value.phoneBattery,
                    phoneGps = it.getStringExtra("phoneGps") ?: _uiState.value.phoneGps,
                    phoneSignal = it.getStringExtra("phoneSignal") ?: _uiState.value.phoneSignal,
                    phoneNetworkType = it.getStringExtra("phoneNetworkType") ?: _uiState.value.phoneNetworkType,
                    phoneHeading = it.getStringExtra("phoneHeading") ?: _uiState.value.phoneHeading
                )
                _uiState.value = newState
            }
        }
    }

    init {
        val filter = IntentFilter(BoatGatewayService.ACTION_STATUS_UPDATE)
        getApplication<Application>().registerReceiver(serviceUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onCleared() {
        getApplication<Application>().unregisterReceiver(serviceUpdateReceiver)
        super.onCleared()
    }
}

data class UiState(
    val usbStatus: String = "Service Stopped",
    val mqttStatus: String = "Service Stopped",
    val webRtcStatus: String = "Idle",
    val lastCommand: String = "None",
    val boatVoltage: String = "-.-- V",
    val boatTacho: String = "---- RPM",
    val phoneBattery: String = "--%",
    val phoneGps: String = "Waiting for fix...",
    val phoneSignal: String = "--",
    val phoneNetworkType: String = "Unknown",
    val phoneHeading: String = "---°"
)

// --- Foreground Service: Manages all connections and background tasks ---
class BoatGatewayService : Service(), SensorEventListener {
    private val binder = LocalBinder()
    private lateinit var sensorManager: SensorManager
    private var usbSerialPort: UsbSerialPort? = null
    private var mqttClient: Mqtt5AsyncClient? = null
    private var serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var phoneTelemetryJob: Job? = null
    private var gravity: FloatArray? = null
    private var geomagnetic: FloatArray? = null
    private var lastGpsFixTime: Long = 0
    private var currentHeading: Int = 0

    // WebRTC Components
    private val eglBase = EglBase.create()
    private val peerConnectionFactory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions())
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var controlDataChannel: DataChannel? = null
    private var telemetryDataChannel: DataChannel? = null
    private var isWebRTCConnecting = false
    private var isWebRTCDisconnecting = AtomicBoolean(false)


    inner class LocalBinder : Binder() {
        fun getService(): BoatGatewayService = this@BoatGatewayService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d("BoatGatewayService", "Service onCreate")
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("BoatGatewayService", "Service onStartCommand: ${intent?.action}")
        when (intent?.action) {
            ACTION_START -> startGateway()
            ACTION_STOP -> stopGateway()
        }
        return START_NOT_STICKY
    }

    private fun startGateway() {
        Log.d("BoatGatewayService", "Starting Gateway Service...")
        startForeground(NOTIFICATION_ID, createNotification())
        startSensorListeners()
        connectUsb()
        connectMqtt()
        Log.d("BoatGatewayService", "Gateway Service Started")
    }

    private fun stopGateway() {
        Log.d("BoatGatewayService", "Stopping Gateway Service...")
        sendSignalingMessage(JSONObject().put("type", "bye"))
        disconnectWebRTC()
        disconnectUsb()
        disconnectMqtt()
        stopSensorListeners()
        serviceJob.cancel()
        stopForeground(true)
        stopSelf()
        Log.d("BoatGatewayService", "Gateway Service Stopped")
    }

    private fun connectUsb() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            updateStatus(usbStatus = "No USB device found")
            return
        }
        val driver = availableDrivers[0]
        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            updateStatus(usbStatus = "USB permission needed")
            return
        }
        usbSerialPort = driver.ports[0]
        try {
            usbSerialPort?.open(connection)
            usbSerialPort?.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            updateStatus(usbStatus = "Connected")
        } catch (e: Exception) {
            updateStatus(usbStatus = "Error: ${e.message}")
            disconnectUsb()
        }
    }

    private fun writeToUsb(data: String) {
        if (usbSerialPort?.isOpen == false) return
        serviceScope.launch {
            try {
                usbSerialPort?.write(data.toByteArray(), 500)
                updateStatus(lastCommand = data.trim())
            } catch (e: Exception) {
                updateStatus(usbStatus = "Write Error")
            }
        }
    }

    private fun disconnectUsb() {
        try { usbSerialPort?.close() } catch (_: Exception) {}
        usbSerialPort = null
        updateStatus(usbStatus = "Disconnected")
    }

    private fun connectMqtt() {
        updateStatus(mqttStatus = "Connecting...")
        mqttClient = MqttClient.builder()
            .useMqttVersion5()
            .identifier("BoatGateway-${UUID.randomUUID()}")
            .serverHost(BuildConfig.MQTT_BROKER_HOST)
            .serverPort(8883)
            .sslWithDefaultConfig()
            .automaticReconnectWithDefaultConfig()
            .addConnectedListener {
                serviceScope.launch {
                    updateStatus(mqttStatus = "Connected")
                    subscribeToSignaling()
                    // Announce presence
                    mqttClient?.publishWith()?.topic("rcboat/status")?.payload("online".toByteArray())?.send()
                }
            }
            .addDisconnectedListener {
                updateStatus(mqttStatus = "Disconnected")
            }
            .buildAsync()

        mqttClient?.connectWith()
            ?.simpleAuth()
            ?.username(BuildConfig.MQTT_USERNAME)
            ?.password(BuildConfig.MQTT_PASSWORD.toByteArray())
            ?.applySimpleAuth()
            ?.send()
            ?.whenComplete { _, throwable: Throwable? ->
                if (throwable != null) {
                    updateStatus(mqttStatus = "Failed: ${throwable.message}")
                }
            }
    }

    private fun subscribeToSignaling() {
        mqttClient?.subscribeWith()
            ?.topicFilter("rcboat/signaling/base_to_boat")
            ?.callback { publish ->
                if (publish.payload.isPresent) {
                    val message = StandardCharsets.UTF_8.decode(publish.payload.get()).toString()
                    val json = JSONObject(message)
                    when {
                        json.has("type") && json.getString("type") == "bye" -> {
                            disconnectWebRTC()
                        }
                        json.has("sdp") -> {
                            val sdp = json.getString("sdp")
                            val type = SessionDescription.Type.fromCanonicalForm(json.getString("type").lowercase())
                            if (type == SessionDescription.Type.OFFER) {
                                if (peerConnection != null) {
                                    disconnectWebRTC()
                                }
                                handleOffer(SessionDescription(type, sdp))
                            }
                        }
                        json.has("candidate") -> {
                            val candidate = IceCandidate(
                                json.getString("sdpMid"),
                                json.getInt("sdpMLineIndex"),
                                json.getString("candidate")
                            )
                            peerConnection?.addIceCandidate(candidate)
                        }
                    }
                }
            }
            ?.send()
    }

    private fun sendSignalingMessage(message: JSONObject) {
        if (mqttClient?.state?.isConnected == true) {
            mqttClient?.publishWith()?.topic("rcboat/signaling/boat_to_base")?.payload(message.toString().toByteArray())?.send()
        }
    }

    private fun handleOffer(offer: SessionDescription) {
        if (isWebRTCConnecting || isWebRTCDisconnecting.get()) {
            Log.w("BoatGatewayService", "Ignoring new offer, already connecting or disconnecting.")
            return
        }
        isWebRTCConnecting = true
        updateStatus(webRtcStatus = "Handshake...")

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("turn:global.turn.twilio.com:3478?transport=udp")
                .setUsername(BuildConfig.TURN_USERNAME)
                .setPassword(BuildConfig.TURN_PASSWORD)
                .createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    val json = JSONObject().apply {
                        put("candidate", it.sdp)
                        put("sdpMid", it.sdpMid)
                        put("sdpMLineIndex", it.sdpMLineIndex)
                    }
                    sendSignalingMessage(json)
                }
            }

            override fun onDataChannel(dataChannel: DataChannel?) {
                dataChannel?.let {
                    if (it.label() == "control") {
                        controlDataChannel = it
                        it.registerObserver(object : DataChannel.Observer {
                            override fun onMessage(buffer: DataChannel.Buffer?) {
                                buffer?.let { b ->
                                    val data = ByteArray(b.data.remaining())
                                    b.data.get(data)
                                    val command = String(data, StandardCharsets.UTF_8)
                                    writeToUsb(command)
                                }
                            }
                            override fun onBufferedAmountChange(p0: Long) {}
                            override fun onStateChange() {}
                        })
                    }
                }
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                updateStatus(webRtcStatus = newState?.name ?: "UNKNOWN")
                if(newState == PeerConnection.IceConnectionState.CONNECTED) {
                    isWebRTCConnecting = false
                    startPhoneTelemetryJob()
                }
                if(newState == PeerConnection.IceConnectionState.FAILED || newState == PeerConnection.IceConnectionState.CLOSED) {
                    isWebRTCConnecting = false
                    disconnectWebRTC()
                }
            }

            // Other unused overrides
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            override fun onRenegotiationNeeded() {}
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setupCameraTrack()
            videoTrack?.let {
                if (peerConnection?.connectionState() != PeerConnection.PeerConnectionState.CLOSED) {
                    peerConnection?.addTrack(it)
                }
            }
        }

        telemetryDataChannel = peerConnection?.createDataChannel("telemetry", DataChannel.Init())

        peerConnection?.setRemoteDescription(SdpObserverAdapter(), offer)
        peerConnection?.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(answer: SessionDescription?) {
                peerConnection?.setLocalDescription(SdpObserverAdapter(), answer)
                answer?.let {
                    val json = JSONObject().apply {
                        put("type", it.type.canonicalForm())
                        put("sdp", it.description)
                    }
                    sendSignalingMessage(json)
                }
            }
        }, MediaConstraints())
    }

    private fun setupCameraTrack() {
        val cameraEnumerator = Camera2Enumerator(this)
        val deviceName = cameraEnumerator.deviceNames.firstOrNull { !cameraEnumerator.isFrontFacing(it) }
            ?: cameraEnumerator.deviceNames.first()

        videoCapturer = cameraEnumerator.createCapturer(deviceName, null)
        if (videoCapturer == null) {
            Log.e("BoatGatewayService", "Failed to create camera capturer.")
            disconnectWebRTC()
            return
        }

        videoSource = peerConnectionFactory.createVideoSource(videoCapturer!!.isScreencast)
        videoCapturer!!.initialize(SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext), this, videoSource!!.capturerObserver)
        videoCapturer!!.startCapture(640, 480, 15)
        videoTrack = peerConnectionFactory.createVideoTrack("videoTrack", videoSource)
    }

    private fun disconnectWebRTC() {
        if (isWebRTCDisconnecting.getAndSet(true)) {
            Log.d("BoatGatewayService", "disconnectWebRTC already in progress.")
            return
        }
        Log.d("BoatGatewayService", "Disconnecting WebRTC...")

        phoneTelemetryJob?.cancel()
        phoneTelemetryJob = null

        serviceScope.launch(Dispatchers.IO) {
            try {
                videoCapturer?.stopCapture()
                Log.d("BoatGatewayService", "Video capturer stopped.")
            } catch (e: Exception) {
                Log.e("BoatGatewayService", "Error stopping video capturer", e)
            }

            peerConnection?.close()
            Log.d("BoatGatewayService", "Peer connection closed.")

            videoCapturer?.dispose()
            videoSource?.dispose()

            peerConnection = null
            videoCapturer = null
            videoSource = null
            videoTrack = null
            controlDataChannel = null
            telemetryDataChannel = null

            isWebRTCConnecting = false
            updateStatus(webRtcStatus = "Idle")
            Log.d("BoatGatewayService", "WebRTC Disconnected.")
            isWebRTCDisconnecting.set(false)
        }
    }

    private fun disconnectMqtt() {
        mqttClient?.disconnect()
    }

    private fun startPhoneTelemetryJob() {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        phoneTelemetryJob?.cancel()
        phoneTelemetryJob = serviceScope.launch {
            while (true) {
                // Battery
                val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val batLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                updateAndPublish("phone:battery", batLevel.toString())

                // Signal & Network
                if (ContextCompat.checkSelfPermission(this@BoatGatewayService, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                    val signalStrength = tm.signalStrength?.level ?: -1
                    val networkType = getNetworkTypeString(tm.dataNetworkType)
                    updateAndPublish("phone:signal", signalStrength.toString())
                    updateAndPublish("phone:network_type", networkType)
                }

                // GPS
                if (ContextCompat.checkSelfPermission(this@BoatGatewayService, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                        .addOnSuccessListener { location: Location? ->
                            if (location != null) {
                                val gpsString = String.format("%.6f, %.6f", location.latitude, location.longitude)
                                updateAndPublish("phone:gps", gpsString)
                                lastGpsFixTime = System.currentTimeMillis()
                            }
                        }
                    if (System.currentTimeMillis() - lastGpsFixTime > 30000) {
                        updateAndPublish("phone:gps", "Stale Fix")
                    }
                }

                // Compass
                updateAndPublish("phone:compass", currentHeading.toString())

                delay(5000) // Send telemetry bundle every 5 seconds
            }
        }
    }

    private fun updateAndPublish(key: String, value: String) {
        // Update local UI state
        when(key) {
            "phone:battery" -> updateStatus(phoneBattery = "$value%")
            "phone:signal" -> updateStatus(phoneSignal = "Level: $value/4")
            "phone:network_type" -> updateStatus(phoneNetworkType = value)
            "phone:gps" -> updateStatus(phoneGps = value)
            "phone:compass" -> updateStatus(phoneHeading = "$value°")
        }
        // Send via data channel
        telemetryDataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap("$key:$value".toByteArray()), false))
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> gravity = event.values.clone()
            Sensor.TYPE_MAGNETIC_FIELD -> geomagnetic = event.values.clone()
        }
        if (gravity != null && geomagnetic != null) {
            val r = FloatArray(9)
            val i = FloatArray(9)
            if (SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(r, orientation)
                val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                currentHeading = ((azimuth + 360) % 360).toInt()
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startSensorListeners() {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)
    }

    private fun stopSensorListeners() {
        sensorManager.unregisterListener(this)
    }

    private fun getNetworkTypeString(type: Int): String {
        return when (type) {
            TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
            TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G/LTE"
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            else -> "Unknown"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Boat Gateway Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("RC Boat Gateway")
            .setContentText("Controller service is active.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private fun updateStatus(
        usbStatus: String? = null, mqttStatus: String? = null, webRtcStatus: String? = null,
        lastCommand: String? = null, boatVoltage: String? = null, boatTacho: String? = null,
        phoneBattery: String? = null, phoneGps: String? = null, phoneSignal: String? = null,
        phoneNetworkType: String? = null, phoneHeading: String? = null
    ) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            setPackage(packageName) // Important for security
            usbStatus?.let { putExtra("usbStatus", it) }
            mqttStatus?.let { putExtra("mqttStatus", it) }
            webRtcStatus?.let { putExtra("webRtcStatus", it) }
            lastCommand?.let { putExtra("lastCommand", it) }
            boatVoltage?.let { putExtra("boatVoltage", it) }
            boatTacho?.let { putExtra("boatTacho", it) }
            phoneBattery?.let { putExtra("phoneBattery", it) }
            phoneGps?.let { putExtra("phoneGps", it) }
            phoneSignal?.let { putExtra("phoneSignal", it) }
            phoneNetworkType?.let { putExtra("phoneNetworkType", it) }
            phoneHeading?.let { putExtra("phoneHeading", it) }
        }
        sendBroadcast(intent)
    }

    companion object {
        const val ACTION_START = "com.example.rc_boat_controller_cellular.START"
        const val ACTION_STOP = "com.example.rc_boat_controller_cellular.STOP"
        const val ACTION_STATUS_UPDATE = "com.example.rc_boat_controller_cellular.STATUS_UPDATE"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "BoatGatewayServiceChannel"
    }
}


// --- UI Composables ---
@Composable
fun BoatControllerScreen(viewModel: BoatViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Boat Gateway", style = MaterialTheme.typography.headlineLarge)
        Spacer(modifier = Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                context.startService(Intent(context, BoatGatewayService::class.java).apply { action = BoatGatewayService.ACTION_START })
            }) { Text("Start Service") }
            Button(onClick = {
                context.startService(Intent(context, BoatGatewayService::class.java).apply { action = BoatGatewayService.ACTION_STOP })
            }) { Text("Stop Service") }

            Button(onClick = {
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
                if (availableDrivers.isNotEmpty()) {
                    val device = availableDrivers[0].device
                    val permissionIntent = PendingIntent.getBroadcast(context, 0, Intent("com.example.rc_boat_controller_cellular.USB_PERMISSION"), PendingIntent.FLAG_IMMUTABLE)
                    usbManager.requestPermission(device, permissionIntent)
                }
            }) { Text("Request USB Perm") }
        }

        Spacer(modifier = Modifier.height(16.dp))
        TelemetryDisplay(uiState)
    }
}

@Composable
fun TelemetryDisplay(state: UiState) {
    Card {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Live Status", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            ConnectionStatus("USB Status", state.usbStatus)
            ConnectionStatus("MQTT Status", state.mqttStatus)
            ConnectionStatus("WebRTC Status", state.webRtcStatus)
            Divider(modifier = Modifier.padding(vertical = 4.dp))
            TelemetryRow("Last Command", state.lastCommand)
            Divider(modifier = Modifier.padding(vertical = 4.dp))
            TelemetryRow("Boat Voltage", state.boatVoltage)
            TelemetryRow("Boat Tacho", state.boatTacho)
            Divider(modifier = Modifier.padding(vertical = 4.dp))
            TelemetryRow("Phone Battery", state.phoneBattery)
            TelemetryRow("Phone Signal", state.phoneSignal)
            TelemetryRow("Phone Network", state.phoneNetworkType)
            TelemetryRow("Phone Heading", state.phoneHeading)
            TelemetryRow("Phone GPS", state.phoneGps)
        }
    }
}

@Composable
fun ConnectionStatus(label: String, status: String) {
    val color = when (status) {
        "Connected" -> Color(0xFF4CAF50) // Green
        "Connecting...", "Handshake..." -> Color(0xFFFFC107) // Amber
        else -> Color.Red
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontWeight = FontWeight.Bold)
        Text(status, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontWeight = FontWeight.Bold)
        Text(value, fontSize = 14.sp)
    }
}

@Composable
fun RCBoatControllerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF3F51B5),
            secondary = Color(0xFF03A9F4),
            tertiary = Color(0xFF009688)
        ),
        content = content
    )
}

// SdpObserver adapter to simplify callbacks
open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}

