package com.example.udpvibrator

import android.content.Context

data class VibeSettings(
    // Receive
    val port: Int = 5005,
    val allowedIp: String = "",   // empty = accept from any sender
    val durationMs: Int = 300,
    val strength: Int = 255,      // 1..255 (only on devices with amplitude control)
    val repeats: Int = 1,         // pulses per signal
    val gapMs: Int = 150,         // pause between pulses
    // Send
    val targetIp: String = "",
    val targetPort: Int = 5005,
    val sendIntervalMs: Int = 200, // repeat rate while the button is held
)

class SettingsStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = VibeSettings(
        port = prefs.getInt("port", 5005),
        allowedIp = prefs.getString("allowedIp", "") ?: "",
        durationMs = prefs.getInt("durationMs", 300),
        strength = prefs.getInt("strength", 255),
        repeats = prefs.getInt("repeats", 1),
        gapMs = prefs.getInt("gapMs", 150),
        targetIp = prefs.getString("targetIp", "") ?: "",
        targetPort = prefs.getInt("targetPort", 5005),
        sendIntervalMs = prefs.getInt("sendIntervalMs", 200),
    )

    fun save(s: VibeSettings) {
        prefs.edit()
            .putInt("port", s.port)
            .putString("allowedIp", s.allowedIp)
            .putInt("durationMs", s.durationMs)
            .putInt("strength", s.strength)
            .putInt("repeats", s.repeats)
            .putInt("gapMs", s.gapMs)
            .putString("targetIp", s.targetIp)
            .putInt("targetPort", s.targetPort)
            .putInt("sendIntervalMs", s.sendIntervalMs)
            .apply()
    }
}

/**
 * Tiny message format between sender and receiver.
 *  "buzz" (or anything else) -> vibrate with the receiver's own settings
 *  "S<1-100>"                -> vibrate with that strength percent
 *  "P1" / "P2" / "P3"        -> short / double / long pattern
 */
object Protocol {
    const val PLAIN = "buzz"
    const val SHORT = "P1"
    const val DOUBLE = "P2"
    const val LONG = "P3"

    fun strength(percent: Int) = "S${percent.coerceIn(1, 100)}"

    fun apply(base: VibeSettings, message: String): VibeSettings {
        val m = message.trim().uppercase()
        return when {
            m == SHORT -> base.copy(durationMs = 100, repeats = 1)
            m == DOUBLE -> base.copy(durationMs = 100, repeats = 2, gapMs = 100)
            m == LONG -> base.copy(durationMs = 1000, repeats = 1)
            m.startsWith("S") -> m.drop(1).toIntOrNull()
                ?.let { base.copy(strength = (it.coerceIn(1, 100) * 255 / 100).coerceAtLeast(1)) }
                ?: base
            else -> base
        }
    }
}
