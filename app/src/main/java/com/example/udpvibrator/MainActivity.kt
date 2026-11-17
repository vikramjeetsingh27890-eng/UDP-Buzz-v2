package com.example.udpvibrator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { AppTheme { MainScreen() } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private fun localIpv4(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .sortedByDescending { it.name.startsWith("wlan") }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        ?.hostAddress
}.getOrNull()

/** Sends a UDP packet immediately, then keeps repeating until stop() is called. */
class UdpSender {
    private var run: AtomicBoolean? = null
    private var thread: Thread? = null

    val isRunning: Boolean get() = run?.get() == true

    fun start(
        ip: String,
        port: Int,
        intervalMs: Long,
        payload: () -> String,
        onSent: () -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
        val flag = AtomicBoolean(true)
        run = flag
        thread = Thread {
            var socket: DatagramSocket? = null
            try {
                val addr = InetAddress.getByName(ip)
                socket = DatagramSocket()
                do {
                    val data = payload().toByteArray()
                    socket.send(DatagramPacket(data, data.size, addr, port))
                    onSent()
                    Thread.sleep(intervalMs)
                } while (flag.get())
            } catch (_: InterruptedException) {
                // stopped
            } catch (e: Exception) {
                onError(e.message ?: "Send failed")
            } finally {
                flag.set(false)
                socket?.close()
            }
        }.also { it.start() }
    }

    fun stop() {
        run?.set(false)
        thread?.interrupt()
        run = null
        thread = null
    }

    companion object {
        /** Sends exactly one packet. */
        fun once(
            ip: String,
            port: Int,
            message: String,
            onSent: () -> Unit,
            onError: (String) -> Unit,
        ) {
            Thread {
                try {
                    val addr = InetAddress.getByName(ip)
                    DatagramSocket().use { sock ->
                        val data = message.toByteArray()
                        sock.send(DatagramPacket(data, data.size, addr, port))
                    }
                    onSent()
                } catch (e: Exception) {
                    onError(e.message ?: "Send failed")
                }
            }.start()
        }
    }
}

@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val store = remember { SettingsStore(ctx) }
    var s by remember { mutableStateOf(store.load()) }
    var tab by remember { mutableIntStateOf(0) }

    fun update(n: VibeSettings) {
        s = n
        store.save(n)
    }

    Surface(Modifier.fillMaxSize()) {
        Column {
            Text(
                "UDP Vibrator",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 12.dp)
            )
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Receive") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Send") })
            }
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (tab == 0) ReceiveTab(s, ::update) else SendTab(s, ::update)
            }
        }
    }
}

@Composable
private fun ReceiveTab(s: VibeSettings, update: (VibeSettings) -> Unit) {
    val ctx = LocalContext.current
    var portText by remember { mutableStateOf(s.port.toString()) }
    val state by UdpService.state.collectAsState()
    val ip = remember(state.running) { localIpv4() ?: "unknown" }
    val hasAmplitude = remember { Haptics.hasAmplitudeControl(ctx) }
    val portValid = portText.toIntOrNull()?.let { it in 1024..65535 } == true

    // Status
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (state.running) "Listening" else "Stopped",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                if (state.running) "Send to $ip:${state.port}" else "Device IP: $ip",
                style = MaterialTheme.typography.bodyLarge
            )
            if (state.running) {
                Text(
                    "Signals: ${state.received}" +
                        (state.lastFrom?.let { "  ·  last from $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    Button(
        onClick = {
            if (state.running) UdpService.stop(ctx) else UdpService.start(ctx)
        },
        enabled = state.running || portValid,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        Text(if (state.running) "Stop" else "Start listening")
    }

    // Network settings
    Text("Network", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = portText,
        onValueChange = { v ->
            portText = v.filter(Char::isDigit).take(5)
            portText.toIntOrNull()?.takeIf { it in 1024..65535 }
                ?.let { update(s.copy(port = it)) }
        },
        label = { Text("UDP port") },
        supportingText = { if (!portValid) Text("Use 1024–65535") },
        isError = !portValid,
        enabled = !state.running,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = s.allowedIp,
        onValueChange = { update(s.copy(allowedIp = it.trim())) },
        label = { Text("Only accept from IP (optional)") },
        placeholder = { Text("Any sender") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth()
    )

    // Vibration settings
    Text("Vibration", style = MaterialTheme.typography.titleMedium)
    LabeledSlider("Duration", "${s.durationMs} ms", s.durationMs, 20..2000, step = 10) {
        update(s.copy(durationMs = it))
    }
    LabeledSlider(
        "Strength",
        if (hasAmplitude) "${(s.strength * 100f / 255).roundToInt()}%" else "not supported",
        s.strength, 1..255, enabled = hasAmplitude
    ) { update(s.copy(strength = it)) }
    LabeledSlider("Pulses", "${s.repeats}", s.repeats, 1..10) {
        update(s.copy(repeats = it))
    }
    LabeledSlider(
        "Pause between pulses", "${s.gapMs} ms", s.gapMs, 0..1000,
        step = 10, enabled = s.repeats > 1
    ) { update(s.copy(gapMs = it)) }

    OutlinedButton(
        onClick = { Haptics.vibrate(ctx, s) },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Test vibration") }
}

@Composable
private fun SendTab(s: VibeSettings, update: (VibeSettings) -> Unit) {
    val holdSender = remember { UdpSender() }
    val toggleSender = remember { UdpSender() }
    val sliderSender = remember { UdpSender() }
    val counter = remember { AtomicInteger() }
    var sent by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var portText by remember { mutableStateOf(s.targetPort.toString()) }
    var keepSending by remember { mutableStateOf(false) }
    var level by remember { mutableIntStateOf(60) }

    DisposableEffect(Unit) {
        onDispose {
            holdSender.stop()
            toggleSender.stop()
            sliderSender.stop()
        }
    }

    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
    val canSend = port != null && s.targetIp.isNotBlank()
    val onSent: () -> Unit = { sent = counter.incrementAndGet() }

    fun startSending(sender: UdpSender, payload: () -> String) {
        val p = port ?: return
        error = null
        sender.start(s.targetIp, p, s.sendIntervalMs.toLong(), payload, onSent) {
            error = it
            keepSending = false
        }
    }

    fun sendOnce(message: String) {
        val p = port ?: return
        error = null
        UdpSender.once(s.targetIp, p, message, onSent) { error = it }
    }

    Text("Target", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = s.targetIp,
        onValueChange = { update(s.copy(targetIp = it.trim())) },
        label = { Text("Receiver IP address") },
        placeholder = { Text("e.g. 192.168.1.25") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = portText,
        onValueChange = { v ->
            portText = v.filter(Char::isDigit).take(5)
            portText.toIntOrNull()?.takeIf { it in 1..65535 }
                ?.let { update(s.copy(targetPort = it)) }
        },
        label = { Text("Port") },
        supportingText = { if (port == null) Text("Use 1–65535") },
        isError = port == null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
    LabeledSlider("Repeat rate", "${s.sendIntervalMs} ms", s.sendIntervalMs, 50..1000, step = 10) {
        update(s.copy(sendIntervalMs = it))
    }

    Text("Controls", style = MaterialTheme.typography.titleMedium)

    // 1. Momentary hold button
    HoldButton(
        label = if (canSend) "Hold to send" else "Enter IP and port",
        enabled = canSend,
        onPress = { startSending(holdSender) { Protocol.PLAIN } },
        onRelease = { holdSender.stop() }
    )

    // 2. Pattern buttons (one tap = one signal)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Short" to Protocol.SHORT, "Double" to Protocol.DOUBLE, "Long" to Protocol.LONG)
            .forEach { (name, msg) ->
                FilledTonalButton(
                    onClick = { sendOnce(msg) },
                    enabled = canSend,
                    modifier = Modifier.weight(1f)
                ) { Text(name) }
            }
    }

    // 3. Toggle switch (keeps sending until switched off)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Keep sending", style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = keepSending,
            enabled = canSend,
            onCheckedChange = { on ->
                keepSending = on
                if (on) startSending(toggleSender) { Protocol.PLAIN } else toggleSender.stop()
            }
        )
    }

    // 4. Intensity slider (sends while dragging, stops on release)
    Column {
        Text("Intensity: $level%  (drag to send)", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = level.toFloat(),
            onValueChange = {
                level = it.roundToInt()
                if (canSend && !sliderSender.isRunning) {
                    startSending(sliderSender) { Protocol.strength(level) }
                }
            },
            onValueChangeFinished = { sliderSender.stop() },
            valueRange = 1f..100f,
            enabled = canSend
        )
    }

    Text("Sent: $sent", style = MaterialTheme.typography.bodyMedium)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Text(
        "Intensity only changes the vibration on receiver phones that support strength control.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Momentary button: active only while the finger is down. */
@Composable
private fun HoldButton(
    label: String,
    enabled: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val latestPress by rememberUpdatedState(onPress)
    val latestRelease by rememberUpdatedState(onRelease)
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        pressed -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        pressed -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures(onPress = {
                        pressed = true
                        latestPress()
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                            latestRelease()
                        }
                    })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (pressed) "Sending…" else label,
            style = MaterialTheme.typography.titleLarge,
            color = fg
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Int,
    range: IntRange,
    enabled: Boolean = true,
    step: Int = 1,
    onChange: (Int) -> Unit,
) {
    Column {
        Text("$label: $valueText", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value.toFloat(),
            onValueChange = {
                onChange(((it / step).roundToInt() * step).coerceIn(range.first, range.last))
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            enabled = enabled
        )
    }
}
