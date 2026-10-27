package com.dems.vpn.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dems.vpn.BuildConfig
import com.dems.vpn.core.XrayConfigBuilder
import com.dems.vpn.core.XrayCore
import com.dems.vpn.data.Profile
import com.dems.vpn.data.ProfileParser
import com.dems.vpn.data.ProfileStore
import com.dems.vpn.data.SettingsStore
import com.dems.vpn.data.VpnSettings
import com.dems.vpn.service.VpnController
import com.dems.vpn.service.VpnState
import com.dems.vpn.service.VpnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

enum class ImportResult { OK, INVALID, DUPLICATE }

/** Résultat d'une mesure de débit ponctuelle (10 s). */
data class SpeedSample(
    val downBps: Long = 0L,
    val upBps: Long = 0L,
    val seconds: Int = 10
)

/** Mesure de débit réelle (octets/s de l'outbound proxy) pour le graphique. */
data class RatePoint(val downBps: Long, val upBps: Long)

/** Valeur spéciale affichée pendant un test de latence. */
private const val LATENCY_RUNNING = -2L

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val profiles: StateFlow<List<Profile>> = ProfileStore.profiles
    val settings: StateFlow<VpnSettings> = SettingsStore.settings
    val status: StateFlow<VpnStatus> = VpnController.status

    val selected: StateFlow<Profile?> =
        combine(ProfileStore.profiles, ProfileStore.selectedId) { list, id ->
            list.firstOrNull { it.id == id } ?: list.firstOrNull()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Latence mesurée par serveur (ms), -1 en cas d'échec. */
    private val _latencies = MutableStateFlow<Map<String, Long>>(emptyMap())
    val latencies: StateFlow<Map<String, Long>> = _latencies

    private val testing = ConcurrentHashMap.newKeySet<String>()

    /** Recherche du meilleur serveur en cours (moteur de décision). */
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching

    private var benchmarkJob: Job? = null

    /**
     * Historique du débit : uniquement des mesures réelles de l'outbound
     * proxy (2 s par échantillon, 60 points au plus, mémoire seule). Tout
     * arrêt de session vide l'historique : jamais de courbe inventée.
     */
    private val _rateHistory = MutableStateFlow<List<RatePoint>>(emptyList())
    val rateHistory: StateFlow<List<RatePoint>> = _rateHistory

    init {
        viewModelScope.launch(Dispatchers.IO) {
            status.collect { st ->
                if (st.state != VpnState.CONNECTED) {
                    _rateHistory.value = emptyList()
                } else {
                    _rateHistory.value = (_rateHistory.value + RatePoint(st.downloadRate, st.uploadRate))
                        .takeLast(60)
                }
            }
        }
    }

    private val _speedSample = MutableStateFlow<SpeedSample?>(null)
    val speedSample: StateFlow<SpeedSample?> = _speedSample

    private val _speedTesting = MutableStateFlow(false)
    val speedTesting: StateFlow<Boolean> = _speedTesting

    private val _deviceKey = MutableStateFlow(SettingsStore.deviceKey(application))
    val deviceKey: StateFlow<String> = _deviceKey

    private val cachedCoreVersion: String by lazy { XrayCore.version() }

    fun coreVersion(): String = cachedCoreVersion

    fun appVersion(): String = BuildConfig.VERSION_NAME

    fun importLink(text: String): ImportResult {
        val profile = ProfileParser.parse(text) ?: return ImportResult.INVALID
        if (ProfileStore.isDuplicate(profile)) return ImportResult.DUPLICATE
        ProfileStore.add(profile)
        return ImportResult.OK
    }

    fun delete(profile: Profile) {
        ProfileStore.remove(profile.id)
        _latencies.value = _latencies.value - profile.id
    }

    fun select(profile: Profile) {
        ProfileStore.select(profile.id)
    }

    /**
     * Moteur de décision : appui sur « connecter ». Si l'auto-test est activé
     * et qu'il y a plusieurs serveurs, on mesure tout le monde en parallèle et
     * on connecte le plus rapide. Sinon on part direct.
     */
    fun connectSmart(onStart: (Profile) -> Unit) {
        val current = selected.value ?: profiles.value.firstOrNull() ?: return
        val settings = SettingsStore.current()
        val list = profiles.value.filter { it != current }
        if (!settings.autoBench || list.isEmpty()) {
            onStart(current)
            return
        }

        benchmarkJob?.cancel()
        benchmarkJob = viewModelScope.launch {
            _searching.value = true
            try {
                val best = benchmarkAll()
                val choice = best ?: current
                select(choice)
                onStart(choice)
            } finally {
                _searching.value = false
                benchmarkJob = null
            }
        }
    }

    /**
     * Annule la recherche en cours et connecte l'ancien serveur sélectionné :
     * appuyer une seconde fois sur le bouton = « arrête de chercher, connecte ».
     */
    fun cancelSearch(onGiveUp: (Profile) -> Unit) {
        benchmarkJob?.cancel()
        benchmarkJob = null
        _searching.value = false
        selected.value?.let(onGiveUp)
    }

    /**
     * Mesure la latence de tous les serveurs, au plus 2 instances Xray
     * temporaires en parallèle (limite mémoire/CPU du téléphone).
     */
    private suspend fun benchmarkAll(): Profile? = coroutineScope {
        val settings = SettingsStore.current()
        val port = 20_000 + Random.nextInt(10_000)
        val sims = Semaphore(2)
        profiles.value
            .map { profile ->
                async {
                    if (!testing.add(profile.id)) return@async null
                    try {
                        sims.acquire()
                        try {
                            val config = XrayConfigBuilder.build(
                                profile = profile,
                                settings = settings,
                                socksPort = port,
                                httpPort = port + 1,
                                socksUser = "bench",
                                socksPass = "bench"
                            )
                            val ms = XrayCore.measureDelay(config, settings.effectiveTestUrl)
                            _latencies.value = _latencies.value + (profile.id to ms)
                            if (ms > 0) profile to ms else null
                        } finally {
                            // Le finally interne ne s'exécute que si acquire()
                            // a réussi : pas de permis libéré deux fois.
                            sims.release()
                        }
                    } finally {
                        testing.remove(profile.id)
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
            .minByOrNull { it.second }
            ?.first
    }

    /**
     * Débit moyen ↓/↑ sur 10 s, calculé sur les compteurs cumulés du tunnel.
     * Sans connexion active, la mesure reste à zéro.
     */
    fun measureSpeed10s() {
        if (_speedTesting.value) return
        viewModelScope.launch {
            _speedTesting.value = true
            try {
                val start = VpnController.status.value
                val startedAt = SystemClock.elapsedRealtime()
                delay(10_000)
                val end = VpnController.status.value
                val seconds = ((SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)) / 1000.0
                _speedSample.value = SpeedSample(
                    downBps = ((end.downloadTotal - start.downloadTotal) / seconds)
                        .toLong().coerceAtLeast(0L),
                    upBps = ((end.uploadTotal - start.uploadTotal) / seconds)
                        .toLong().coerceAtLeast(0L),
                    seconds = seconds.toInt().coerceAtLeast(1)
                )
            } finally {
                _speedTesting.value = false
            }
        }
    }

    fun updateSettings(settings: VpnSettings) {
        SettingsStore.update(settings)
    }

    fun setPin(pin: String) {
        SettingsStore.setPin(pin)
    }

    fun clearPin() {
        SettingsStore.clearPin()
    }

    fun regenerateDeviceKey() {
        _deviceKey.value = SettingsStore.regenerateDeviceKey(getApplication())
    }

    /**
     * Teste la latence réelle du serveur en lançant une instance Xray
     * temporaire (aucune incidence sur le tunnel en cours).
     */
    /** Test individuel depuis la liste des serveurs (bouton ↻). */
    fun testLatency(profile: Profile) {
        if (!testing.add(profile.id)) return
        _latencies.value = _latencies.value + (profile.id to LATENCY_RUNNING)
        viewModelScope.launch {
            try {
                val current = SettingsStore.current()
                val port = 20_000 + Random.nextInt(10_000)
                val config = XrayConfigBuilder.build(
                    profile = profile,
                    settings = current,
                    socksPort = port,
                    httpPort = port + 1,
                    socksUser = "latency",
                    socksPass = "latency"
                )
                val delayMs = withContext(Dispatchers.IO) {
                    XrayCore.measureDelay(config, current.effectiveTestUrl)
                }
                _latencies.value = _latencies.value + (profile.id to delayMs)
            } finally {
                testing.remove(profile.id)
            }
        }
    }
}
