package com.dems.vpn.core

import android.content.Context
import android.util.Log
import com.dems.vpn.data.SettingsStore
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File

/**
 * Enveloppe Kotlin autour du cœur Xray (libv2ray.aar, AndroidLibXrayLite).
 * Une seule instance de contrôleur est conservée pour tout le processus.
 */
object XrayCore {

    private const val TAG = "DemsXray"
    private val lock = Any()

    private var initialized = false
    private var controller: CoreController? = null

    private val callback = object : CoreCallbackHandler {
        override fun startup(): Long {
            Log.i(TAG, "Core démarré")
            return 0
        }

        override fun shutdown(): Long {
            Log.i(TAG, "Core arrêté")
            return 0
        }

        override fun onEmitStatus(code: Long, message: String?): Long {
            Log.i(TAG, "Statut $code : ${message.orEmpty()}")
            return 0
        }
    }

    fun init(context: Context) {
        synchronized(lock) {
            if (initialized) return
            try {
                Seq.setContext(context.applicationContext)
                val assets = File(context.filesDir, "assets").apply { mkdirs() }
                Libv2ray.initCoreEnv(assets.absolutePath, SettingsStore.deviceKey(context))
                initialized = true
                Log.i(TAG, "Environnement initialisé")
            } catch (t: Throwable) {
                Log.e(TAG, "Initialisation impossible", t)
            }
        }
    }

    fun version(): String = runCatching { Libv2ray.checkVersionX() }
        .getOrDefault("indisponible")

    fun start(context: Context, configJson: String): Result<Unit> {
        init(context)
        if (!initialized) {
            return Result.failure(IllegalStateException("Cœur Xray non initialisé"))
        }
        synchronized(lock) {
            val core = controller ?: Libv2ray.newCoreController(callback).also { controller = it }
            if (core.isRunning) return Result.success(Unit)
            return try {
                core.startLoop(configJson, 0)
                if (core.isRunning) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("Configuration refusée par le cœur"))
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Échec du démarrage", t)
                Result.failure(t)
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            val core = controller ?: return
            if (!core.isRunning) return
            runCatching { core.stopLoop() }
                .onFailure { Log.w(TAG, "Échec de l'arrêt", it) }
        }
    }

    fun isRunning(): Boolean = synchronized(lock) { controller?.isRunning == true }

    /** Latence HTTP (ms) mesurée par le cœur avec une config de test. -1 si échec. */
    fun measureDelay(configJson: String, url: String): Long =
        runCatching { Libv2ray.measureOutboundDelay(configJson, url) }.getOrDefault(-1L)

    data class TrafficCounters(val uplink: Long = 0L, val downlink: Long = 0L)

    /**
     * Compteurs cumulés depuis le dernier appel (les compteurs Xray sont remis
     * à zéro par `queryAllOutboundTrafficStats`).
     */
    fun traffic(): TrafficCounters = synchronized(lock) {
        val raw = runCatching { controller?.queryAllOutboundTrafficStats() }
            .getOrNull()
            .orEmpty()
        var up = 0L
        var down = 0L
        raw.split(';').forEach { entry ->
            val parts = entry.split(',')
            if (parts.size != 3) return@forEach
            val value = parts[2].toLongOrNull() ?: return@forEach
            when (parts[1]) {
                "uplink" -> up += value
                "downlink" -> down += value
            }
        }
        TrafficCounters(up, down)
    }
}
