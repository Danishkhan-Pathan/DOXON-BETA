package com.example.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class HapticHelper(private val context: Context) {
    
    private val sharedPrefs = context.getSharedPreferences("doxon_settings", Context.MODE_PRIVATE)

    fun getHapticProfile(): String {
        return sharedPrefs.getString("haptic_profile", "sharp") ?: "sharp" // "soft", "sharp", "disabled"
    }

    fun setHapticProfile(profile: String) {
        sharedPrefs.edit().putString("haptic_profile", profile).apply()
    }

    fun triggerVibration() {
        val profile = getHapticProfile()
        if (profile == "disabled") return
        
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (vibrator.hasVibrator()) {
                val durationMs: Long
                val amplitude: Int
                
                if (profile == "soft") {
                    durationMs = 8L
                    amplitude = 40
                } else { // "sharp"
                    durationMs = 25L
                    amplitude = 220
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255)))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    fun triggerSoftVibration() {
        val profile = getHapticProfile()
        if (profile != "soft") return // Soft mode is intended to sync with real-time text stream delivery
        
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(5, 20))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(5)
                }
            }
        } catch (e: Throwable) {
            // Ignore
        }
    }
}
