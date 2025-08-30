package com.example.rc_boat_controller_cellular

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
import com.hoho.android.usbserial.driver.UsbSerialDriver
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

// --- Main Activity: UI and Service Control ---
class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()

        // Start the service as soon as the app is opened
        Intent(this, BoatGatewayService::class.java).also { intent ->
            startForegroundService(intent)
        }

        setContent {
            RCBoatControllerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BoatControllerScreen()
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissionsToRequest = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CAMERA,
            Manifest.permission.POST_NOTIFICATIONS // Required for foreground service on newer Android
        )
        requestPermissionLauncher.launch(permissionsToRequest)
    }
}

// --- ViewModel: Holds UI state and listens for updates from the Service ---
class BoatViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val serviceUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                _uiState.value = UiState(
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
    val usbStatus: String = "Initializing...",
    val mqttStatus: String = "Initializing...",
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
    private var serviceJob: Job? = null
    private var usbReadJob: Job? = null
    private var gravity: FloatArray? = null
    private var geomagnetic: FloatArray? = null
    private var lastGpsFixTime: Long = 0
    private var currentPhoneHeading: String = "---°"

    // --- WebRTC Components ---
    private val eglBase = EglBase.create()
    private val peerConnectionFactory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions())
        PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()
    }
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var controlDataChannel: DataChannel? = null
    private var telemetryDataChannel: DataChannel? = null
    private var isWebRTCActive = false


    inner class LocalBinder : Binder() {
        fun getService(): BoatGatewayService = this@BoatGatewayService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        createNotificationChannel()
        startGateway()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_FROM_NOTIFICATION) {
            stopGateway()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopGateway()
        super.onDestroy()
    }

    private fun startGateway() {
        Log.d(TAG, "Starting Gateway Service...")
        startForeground(NOTIFICATION_ID, createNotification("Initializing..."))

        serviceJob = CoroutineScope(Dispatchers.IO).launch {
            connectMqtt()
            startSensorListeners()
            launch { periodicUsbCheck() }
            launch { periodicTelemetryUpdate() }
        }

        Log.d(TAG, "Gateway Service Started")
    }

    private fun stopGateway() {
        Log.d(TAG, "Stopping Gateway Service...")
        serviceJob?.cancel()
        disconnectUsb()
        disconnectMqtt()
        stopSensorListeners()
        cleanupWebRTC()
        stopForeground(true)
        stopSelf()
        Log.d(TAG, "Gateway Service Stopped")
    }

    // --- Connection and Telemetry Logic ---
    private suspend fun periodicUsbCheck() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        while (true) {
            if (usbSerialPort == null || usbSerialPort?.isOpen == false) {
                val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
                if (availableDrivers.isNotEmpty()) {
                    val driver = availableDrivers[0]
                    if (usbManager.hasPermission(driver.device)) {
                        connectUsb(usbManager, driver)
                    } else {
                        updateStatus(usbStatus = "Permission Needed")
                    }
                } else {
                    updateStatus(usbStatus = "Disconnected")
                }
            }
            delay(5000) // Check every 5 seconds
        }
    }

    private fun connectUsb(usbManager: UsbManager, driver: UsbSerialDriver) {
        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            updateStatus(usbStatus = "Failed to open device")
            return
        }

        usbSerialPort = driver.ports[0]
        try {
            usbSerialPort?.open(connection)
            usbSerialPort?.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            updateStatus(usbStatus = "Connected")
            startReadingUsbData()
        } catch (e: Exception) {
            updateStatus(usbStatus = "Error: ${e.message}")
            disconnectUsb()
        }
    }

    private fun startReadingUsbData() {
        usbReadJob?.cancel()
        usbReadJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(1024)
            var lineBuffer = ""
            while (usbSerialPort?.isOpen == true) {
                try {
                    val len = usbSerialPort?.read(buffer, 200) ?: 0
                    if (len > 0) {
                        lineBuffer += String(buffer, 0, len)
                        while (lineBuffer.contains("\n")) {
                            val line = lineBuffer.substringBefore("\n").trim()
                            lineBuffer = lineBuffer.substringAfter("\n")
                            parseAndSendStmTelemetry(line)
                        }
                    }
                } catch (e: Exception) {
                    updateStatus(usbStatus = "Disconnected")
                    break
                }
            }
        }
    }

    private fun writeToUsb(data: String) {
        if (usbSerialPort?.isOpen == true) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    usbSerialPort?.write(data.toByteArray(), 500)
                    updateStatus(lastCommand = data.trim())
                } catch (e: Exception) {
                    updateStatus(usbStatus = "Write Error")
                }
            }
        }
    }

    private fun disconnectUsb() {
        usbReadJob?.cancel()
        try {
            usbSerialPort?.close()
        } catch (_: Exception) {}
        usbSerialPort = null
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
                Log.d(TAG, "MQTT reconnected, sending hello.")
                updateStatus(mqttStatus = "Connected")
                subscribeToSignaling()
                sendSignalingMessage(JSONObject().put("type", "hello"))
            }
            .addDisconnectedListener {
                Log.d(TAG, "MQTT disconnected.")
                cleanupWebRTC()
            }
            .buildAsync()

        mqttClient?.connectWith()
            ?.simpleAuth()
            ?.username(BuildConfig.MQTT_USERNAME)
            ?.password(BuildConfig.MQTT_PASSWORD.toByteArray())
            ?.applySimpleAuth()
            ?.send()
    }

    private fun sendDataChannelTelemetry(topic: String, payload: String) {
        if (telemetryDataChannel?.state() == DataChannel.State.OPEN) {
            val message = "$topic:$payload"
            val buffer = ByteBuffer.wrap(message.toByteArray(StandardCharsets.UTF_8))
            telemetryDataChannel?.send(DataChannel.Buffer(buffer, false))
        }
    }

    private fun disconnectMqtt() {
        mqttClient?.disconnect()
    }

    private fun parseAndSendStmTelemetry(line: String) {
        when {
            line.startsWith("V") -> {
                val value = line.substring(1)
                updateStatus(boatVoltage = "$value V")
                sendDataChannelTelemetry("rcboat/telemetry/stm32/voltage", value)
            }
            line.startsWith("T") -> {
                val value = line.substring(1)
                updateStatus(boatTacho = "$value RPM")
                sendDataChannelTelemetry("rcboat/telemetry/stm32/tacho", value)
            }
        }
    }

    private suspend fun periodicTelemetryUpdate() {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        while (true) {
            // Battery
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            updateStatus(phoneBattery = "$batLevel%")
            sendDataChannelTelemetry("rcboat/telemetry/phone/battery", batLevel.toString())

            // Signal & Network
            if (ContextCompat.checkSelfPermission(this@BoatGatewayService, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val signalStrength = tm.signalStrength?.level ?: -1
                val networkType = getNetworkTypeString(tm.dataNetworkType)
                updateStatus(phoneSignal = "Level: $signalStrength/4", phoneNetworkType = networkType)
                sendDataChannelTelemetry("rcboat/telemetry/phone/signal", signalStrength.toString())
                sendDataChannelTelemetry("rcboat/telemetry/phone/network_type", networkType)
            }

            // GPS with Stale Data Check
            if (ContextCompat.checkSelfPermission(this@BoatGatewayService, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                    .addOnSuccessListener { location: Location? ->
                        if (location != null) {
                            val gpsString = String.format("%.6f, %.6f", location.latitude, location.longitude)
                            updateStatus(phoneGps = gpsString)
                            sendDataChannelTelemetry("rcboat/telemetry/phone/gps", gpsString)
                            lastGpsFixTime = System.currentTimeMillis()
                        }
                    }
                if (System.currentTimeMillis() - lastGpsFixTime > 30000) {
                    updateStatus(phoneGps = "Stale Fix")
                    sendDataChannelTelemetry("rcboat/telemetry/phone/gps", "Stale Fix")
                }
            }

            val headingToPublish = currentPhoneHeading.removeSuffix("°")
            if(headingToPublish != "---"){
                sendDataChannelTelemetry("rcboat/telemetry/phone/compass", headingToPublish)
            }

            delay(10000)
        }
    }

    // --- Sensor and Notification Logic ---
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> gravity = event.values.clone()
            Sensor.TYPE_MAGNETIC_FIELD -> geomagnetic = event.values.clone()
        }

        if (gravity != null && geomagnetic != null) {
            val r = FloatArray(9)
            val i = FloatArray(9)
            val success = SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)
            if (success) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(r, orientation)
                val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                val heading = (azimuth + 360) % 360
                currentPhoneHeading = "${heading.toInt()}°"
                updateStatus(phoneHeading = currentPhoneHeading)
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startSensorListeners() {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        accelerometer?.also { accel ->
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
        }
        magnetometer?.also { mag ->
            sensorManager.registerListener(this, mag, SensorManager.SENSOR_DELAY_UI)
        }
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
        val channel = NotificationChannel(CHANNEL_ID, "Boat Gateway Service", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(contentText: String): Notification {
        val stopIntent = Intent(this, BoatGatewayService::class.java).apply {
            action = ACTION_STOP_FROM_NOTIFICATION
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RC Boat Gateway")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText))
    }

    // --- WebRTC Signaling and Setup ---
    private fun subscribeToSignaling() {
        mqttClient?.subscribeWith()
            ?.topicFilter("rcboat/signaling/base_to_boat")
            ?.callback { publish ->
                if (publish.payload.isPresent) {
                    val message = StandardCharsets.UTF_8.decode(publish.payload.get()).toString()
                    Log.d(TAG, "Received message: $message")
                    val json = JSONObject(message)
                    when {
                        json.has("type") && json.getString("type") == "bye" -> {
                            Log.d(TAG, "Received BYE, closing connection")
                            cleanupWebRTC()
                        }
                        json.has("sdp") -> {
                            val sdp = json.getString("sdp")
                            val type = SessionDescription.Type.fromCanonicalForm(json.getString("type").lowercase())
                            if (type == SessionDescription.Type.OFFER) {
                                if (isWebRTCActive) {
                                    Log.d(TAG, "Received new offer, restarting WebRTC connection.")
                                    cleanupWebRTC()
                                }
                                Log.d(TAG, "Received OFFER")
                                handleOffer(SessionDescription(type, sdp))
                            }
                        }
                        json.has("candidate") -> {
                            Log.d(TAG, "Received ICE Candidate")
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
        Log.d(TAG, "Sending message: $message")
        if (mqttClient?.state?.isConnected == true) {
            mqttClient?.publishWith()?.topic("rcboat/signaling/boat_to_base")?.payload(message.toString().toByteArray())?.send()
        }
    }

    private fun cleanupWebRTC() {
        Log.d(TAG, "Cleaning up WebRTC connection...")
        try {
            peerConnection?.close()
            controlDataChannel?.close()
            telemetryDataChannel?.close()
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Error during WebRTC cleanup", e)
        } finally {
            peerConnection = null
            videoCapturer = null
            controlDataChannel = null
            telemetryDataChannel = null
            isWebRTCActive = false
            updateStatus(webRtcStatus = "Idle")
        }
    }


    private fun handleOffer(offerSdp: SessionDescription) {
        if (isWebRTCActive) {
            Log.w(TAG, "Ignoring new offer, connection already in progress.")
            return
        }
        isWebRTCActive = true
        updateStatus(webRtcStatus = "Connecting...")

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission not granted, cannot handle offer.")
            isWebRTCActive = false
            updateStatus(webRtcStatus = "No Camera Permission")
            return
        }

        // Bring the UI to the foreground to ensure camera access is allowed
        val mainActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(mainActivityIntent)


        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("turn:numb.viagenie.ca:3478")
                .setUsername("webrtc@live.com")
                .setPassword("muazkh")
                .createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    Log.d(TAG, "Generated local ICE Candidate")
                    val json = JSONObject().apply {
                        put("candidate", it.sdp)
                        put("sdpMid", it.sdpMid)
                        put("sdpMLineIndex", it.sdpMLineIndex)
                    }
                    sendSignalingMessage(json)
                }
            }
            override fun onDataChannel(dataChannel: DataChannel?) {
                controlDataChannel = dataChannel
                controlDataChannel?.registerObserver(object: DataChannel.Observer {
                    override fun onBufferedAmountChange(p0: Long) {}
                    override fun onStateChange() {}
                    override fun onMessage(buffer: DataChannel.Buffer?) {
                        buffer?.let {
                            val data = ByteArray(it.data.remaining())
                            it.data.get(data)
                            val command = String(data, StandardCharsets.UTF_8)
                            writeToUsb(command)
                        }
                    }
                })
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                super.onConnectionChange(newState)
                val status = newState?.name ?: "Unknown"
                updateStatus(webRtcStatus = status)
                if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                    isWebRTCActive = true
                }
                if (newState == PeerConnection.PeerConnectionState.FAILED || newState == PeerConnection.PeerConnectionState.DISCONNECTED || newState == PeerConnection.PeerConnectionState.CLOSED) {
                    cleanupWebRTC()
                }
            }
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
        })

        telemetryDataChannel = peerConnection?.createDataChannel("telemetry", DataChannel.Init())
        setupCameraTrack()

        peerConnection?.setRemoteDescription(SdpObserverAdapter(), offerSdp)
        peerConnection?.createAnswer(object: SdpObserverAdapter() {
            override fun onCreateSuccess(answerSdp: SessionDescription?) {
                Log.d(TAG, "Created ANSWER")
                peerConnection?.setLocalDescription(object: SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Set local description (answer) success")
                        val json = JSONObject().apply {
                            put("type", answerSdp?.type?.canonicalForm())
                            put("sdp", answerSdp?.description)
                        }
                        sendSignalingMessage(json)
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "Failed to set local description: $error")
                        cleanupWebRTC()
                    }
                }, answerSdp)
            }
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Failed to create answer: $error")
                cleanupWebRTC()
            }
        }, MediaConstraints())
    }

    private fun setupCameraTrack() {
        val surfaceTextureHelper = SurfaceTextureHelper.create("VideoCapturerThread", eglBase.eglBaseContext)
        videoCapturer = createCameraCapturer()

        if (videoCapturer == null) {
            Log.e(TAG, "Failed to create camera capturer.")
            return
        }

        val videoSource = peerConnectionFactory.createVideoSource(videoCapturer!!.isScreencast)
        videoCapturer!!.initialize(surfaceTextureHelper, this, videoSource.capturerObserver)
        videoCapturer!!.startCapture(640, 480, 15)

        val videoTrack = peerConnectionFactory.createVideoTrack("videoTrack", videoSource)
        peerConnection?.addTrack(videoTrack, listOf("stream1"))
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(this)
        val deviceNames = enumerator.deviceNames
        for (deviceName in deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        return null
    }


    private fun updateStatus(
        usbStatus: String? = null, mqttStatus: String? = null, webRtcStatus: String? = null,
        lastCommand: String? = null, boatVoltage: String? = null, boatTacho: String? = null,
        phoneBattery: String? = null, phoneGps: String? = null, phoneSignal: String? = null,
        phoneNetworkType: String? = null, phoneHeading: String? = null
    ) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            setPackage(packageName)
            usbStatus?.let { putExtra("usbStatus", it) }
            mqttStatus?.let { putExtra("mqttStatus", it) }
            webRtcStatus?.let { putExtra("webRtcStatus", it); updateNotification("WebRTC: $it")}
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
        const val TAG = "BoatGatewayService"
        const val CHANNEL_ID = "BoatGatewayServiceChannel"
        const val ACTION_STOP_FROM_NOTIFICATION = "com.example.rc_boat_controller_cellular.STOP_FROM_NOTIFICATION"
        const val ACTION_STATUS_UPDATE = "com.example.rc_boat_controller_cellular.STATUS_UPDATE"
        private const val NOTIFICATION_ID = 1
    }
}


// --- UI Composables ---
@Composable
fun BoatControllerScreen(viewModel: BoatViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val usbPermissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_USB_PERMISSION) {
                    // This just ensures the broadcast is registered, the service handles connection
                }
            }
        }
        context.registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
        onDispose {
            context.unregisterReceiver(usbPermissionReceiver)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("RC Boat Gateway", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))

        Button(onClick = {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            if (availableDrivers.isNotEmpty()) {
                val permissionIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
                usbManager.requestPermission(availableDrivers[0].device, permissionIntent)
            }
        }) {
            Text("Request USB Permission")
        }

        Spacer(Modifier.height(24.dp))

        ConnectionStatus(
            usbStatus = uiState.usbStatus,
            mqttStatus = uiState.mqttStatus,
            webRtcStatus = uiState.webRtcStatus
        )
        Spacer(Modifier.height(16.dp))
        TelemetryDisplay(uiState = uiState)
    }
}

@Composable
fun ConnectionStatus(usbStatus: String, mqttStatus: String, webRtcStatus: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Live Status", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            StatusRow("USB", usbStatus)
            StatusRow("MQTT", mqttStatus)
            StatusRow("WebRTC", webRtcStatus)
        }
    }
}

@Composable
fun TelemetryDisplay(uiState: UiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Telemetry", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            TelemetryRow("Last Command", uiState.lastCommand)
            Divider(modifier = Modifier.padding(vertical = 4.dp))
            TelemetryRow("Boat Voltage", uiState.boatVoltage)
            TelemetryRow("Boat Tacho", uiState.boatTacho)
            Divider(modifier = Modifier.padding(vertical = 4.dp))
            TelemetryRow("Phone Battery", uiState.phoneBattery)
            TelemetryRow("Phone Signal", uiState.phoneSignal)
            TelemetryRow("Phone Network", uiState.phoneNetworkType)
            TelemetryRow("Phone Heading", uiState.phoneHeading)
            TelemetryRow("Phone GPS", uiState.phoneGps)
        }
    }
}

@Composable
fun StatusRow(label: String, status: String) {
    val statusColor = when (status.lowercase()) {
        "connected", "open" -> Color(0xFF00C853) // Green
        "connecting...", "opening..." -> Color.Yellow
        else -> Color.Gray
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = FontWeight.Bold)
        Text(status, color = statusColor)
    }
}

@Composable
fun TelemetryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = FontWeight.Bold)
        Text(value)
    }
}

// --- Theme and Constants ---
private const val ACTION_USB_PERMISSION = "com.example.rc_boat_controller_cellular.USB_PERMISSION"

@Composable
fun RCBoatControllerTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = Color(0xFFBB86FC),
        secondary = Color(0xFF03DAC6),
        tertiary = Color(0xFF3700B3)
    )
    MaterialTheme(colorScheme = colorScheme, content = content)
}

// SdpObserver adapter to simplify callbacks
open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}