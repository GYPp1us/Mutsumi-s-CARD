package com.mutsumi.card.focus

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.concurrent.TimeUnit

/** 此对象不含密钥，允许呈现在设置页或测试报告中。 */
data class FocusConfiguration(val enabled: Boolean = false, val subjectId: Int? = null, val itemId: Int? = null)
data class FocusReporterState(
    val configuration: FocusConfiguration = FocusConfiguration(),
    val hasKey: Boolean = false,
    val catalog: FocusCatalog? = null,
    val message: String = "请配置 408 Dashboard 目录链接。",
    val loading: Boolean = false,
)

interface FocusSettingsStorage {
    suspend fun loadConfiguration(): FocusConfiguration
    suspend fun saveConfiguration(configuration: FocusConfiguration)
    fun loadKey(): String?
    fun saveKey(key: String)
    fun clearKey()
}

/** 密钥只进入 Keystore 加密、noBackupFilesDir 和 HTTPS 请求路径。 */
private val Context.focusReporterDataStore by preferencesDataStore(name = "mutsumi-focus-reporter")

class AndroidFocusSettingsStorage(context: Context) : FocusSettingsStorage {
    private val app = context.applicationContext ?: context
    private val preferences = app.focusReporterDataStore
    private val enabledKey = booleanPreferencesKey("enabled")
    private val subjectKey = intPreferencesKey("subject_id")
    private val itemKey = intPreferencesKey("item_id")
    private val secretFile = AtomicFile(File(app.noBackupFilesDir, "focus-reporter.credential"))
    private val alias = "com.mutsumi.card.focus-reporter-key"

    override suspend fun loadConfiguration(): FocusConfiguration {
        val values = preferences.data.first()
        return FocusConfiguration(
            enabled = values[enabledKey] ?: false,
            subjectId = values[subjectKey]?.takeIf { it > 0 },
            itemId = values[itemKey]?.takeIf { it > 0 },
        )
    }

    override suspend fun saveConfiguration(configuration: FocusConfiguration) {
        preferences.edit {
            it[enabledKey] = configuration.enabled
            it[subjectKey] = configuration.subjectId ?: 0
            it[itemKey] = configuration.itemId ?: 0
        }
    }

    override fun loadKey(): String? {
        val payload = try { secretFile.openRead().use { it.readBytes() } } catch (_: FileNotFoundException) { return null }
        require(payload.size > 13 && payload[0].toInt() == 12) { "专注密钥文件损坏" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, payload.copyOfRange(1, 13)))
        return cipher.doFinal(payload.copyOfRange(13, payload.size)).decodeToString()
    }

    override fun saveKey(key: String) {
        check(app.noBackupFilesDir.isDirectory || app.noBackupFilesDir.mkdirs()) { "无法创建专注设置目录" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val payload = byteArrayOf(12) + cipher.iv + cipher.doFinal(key.encodeToByteArray())
        val stream = secretFile.startWrite()
        try {
            stream.write(payload)
            secretFile.finishWrite(stream)
        } catch (error: Exception) {
            secretFile.failWrite(stream)
            throw error
        }
    }

    override fun clearKey() = secretFile.delete()

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
}

class FocusHttpException(val statusCode: Int) : IOException("专注服务 HTTP $statusCode")
interface FocusReporterGateway {
    suspend fun catalog(key: String): FocusCatalog
    suspend fun frame(key: String, body: String): Int
}

class HttpFocusReporterGateway(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
) : FocusReporterGateway {
    override suspend fun catalog(key: String): FocusCatalog = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(FocusProtocol.catalogUrl(key)).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw FocusHttpException(response.code)
            val body = response.body?.string() ?: throw IOException("目录为空")
            FocusCatalog.parse(body)
        }
    }

    override suspend fun frame(key: String, body: String): Int = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(FocusProtocol.frameUrl(key))
            .post(body.toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { it.code }
    }
}

/** 应用级串行状态机：一次只发一帧，页面或前台状态变化立即排入 idle。 */
class FocusReporterController(
    private val storage: FocusSettingsStorage,
    private val gateway: FocusReporterGateway,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private sealed interface Event {
        data class Foreground(val value: Boolean) : Event
        data class Studying(val value: Boolean) : Event
        data class Connect(val input: String) : Event
        data class SelectSubject(val id: Int) : Event
        data class SelectItem(val id: Int) : Event
        data class Enable(val value: Boolean) : Event
        data object Refresh : Event
        data object Tick : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(FocusReporterState())
    val state: StateFlow<FocusReporterState> = mutableState
    private var foreground = false
    private var studying = false
    private var key: String? = null
    private var configuration = FocusConfiguration()
    private var catalog: FocusCatalog? = null
    private var attempted = false
    private var accepted = false
    private var blocked = false
    private var heartbeat: Job? = null

    init {
        scope.launch {
            configuration = withContext(ioDispatcher) { storage.loadConfiguration() }
            key = withContext(ioDispatcher) { storage.loadKey() }
            show(if (key == null) "请配置 408 Dashboard 目录链接。" else "已配置；进入学习页后开始上报。")
            for (event in events) {
                when (event) {
                    is Event.Foreground -> foreground = event.value
                    is Event.Studying -> studying = event.value
                    is Event.Connect -> handleConnect(event.input)
                    is Event.SelectSubject -> handleSelectSubject(event.id)
                    is Event.SelectItem -> handleSelectItem(event.id)
                    is Event.Enable -> enable(event.value)
                    Event.Refresh -> handleRefreshCatalog()
                    Event.Tick -> Unit
                }
                reconcile(event == Event.Tick)
            }
        }
    }

    fun setForeground(value: Boolean) { events.trySend(Event.Foreground(value)).getOrThrow() }
    fun setStudying(value: Boolean) { events.trySend(Event.Studying(value)).getOrThrow() }
    fun connect(input: String) { events.trySend(Event.Connect(input)).getOrThrow() }
    fun selectSubject(id: Int) { events.trySend(Event.SelectSubject(id)).getOrThrow() }
    fun selectItem(id: Int) { events.trySend(Event.SelectItem(id)).getOrThrow() }
    fun setEnabled(value: Boolean) { events.trySend(Event.Enable(value)).getOrThrow() }
    fun refreshCatalog() { events.trySend(Event.Refresh).getOrThrow() }

    private fun show(message: String, loading: Boolean = false) {
        mutableState.value = FocusReporterState(configuration, key != null, catalog, message, loading)
    }

    private suspend fun handleConnect(input: String) {
        show("正在读取目录…", true)
        try {
            val parsed = FocusProtocol.parseKey(input)
            val fetched = gateway.catalog(parsed)
            val subject = fetched.subjects.firstOrNull { it.name == "408" } ?: fetched.subjects.firstOrNull()
            val chosenSubject = configuration.subjectId?.takeIf { id -> fetched.subjects.any { it.id == id } } ?: subject?.id
            val chosenItem = configuration.itemId?.takeIf { id -> chosenSubject != null && fetched.containsSelection(chosenSubject, id) }
                ?: chosenSubject?.let { fetched.itemsForSubject(it).firstOrNull()?.id }
            require(chosenSubject != null && chosenItem != null) { "目录中没有可选科目和事项" }
            stop()
            withContext(ioDispatcher) { storage.saveKey(parsed) }
            key = parsed
            catalog = fetched
            configuration = configuration.copy(subjectId = chosenSubject, itemId = chosenItem)
            withContext(ioDispatcher) { storage.saveConfiguration(configuration) }
            blocked = false
            show("目录已读取，请确认科目和事项。")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: FocusHttpException) {
            show("目录读取失败：HTTP ${error.statusCode}")
        } catch (_: IOException) {
            show("目录读取失败，请检查网络或密钥。")
        } catch (_: IllegalArgumentException) {
            show("目录链接、密钥或内容无效。")
        }
    }

    private suspend fun handleRefreshCatalog() {
        val existingKey = key ?: return show("请先配置目录链接。")
        show("正在刷新目录…", true)
        try {
            val fresh = gateway.catalog(existingKey)
            catalog = fresh
            show(if (selectionValid()) "目录已更新。" else "已选事项不在当前目录，请重新选择。")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: FocusHttpException) {
            show("目录读取失败：HTTP ${error.statusCode}")
        } catch (_: IOException) {
            show("目录读取失败，请检查网络。")
        }
    }

    private suspend fun handleSelectSubject(id: Int) {
        val current = catalog ?: return
        if (current.subjects.none { it.id == id }) return
        stop()
        configuration = configuration.copy(subjectId = id, itemId = current.itemsForSubject(id).firstOrNull()?.id)
        withContext(ioDispatcher) { storage.saveConfiguration(configuration) }
        show("科目已更新。")
    }

    private suspend fun handleSelectItem(id: Int) {
        val subjectId = configuration.subjectId ?: return
        if (catalog?.containsSelection(subjectId, id) != true) return
        stop()
        configuration = configuration.copy(itemId = id)
        withContext(ioDispatcher) { storage.saveConfiguration(configuration) }
        show("事项已更新。")
    }

    private suspend fun enable(value: Boolean) {
        if (value && (key == null || !selectionValid())) return show("请先配置并选择有效目录事项。")
        if (!value) stop()
        configuration = configuration.copy(enabled = value)
        withContext(ioDispatcher) { storage.saveConfiguration(configuration) }
        blocked = false
        show(if (value) "已开启；在学习页且应用处于前台时自动上报。" else "专注上报已关闭。")
    }

    private fun selectionValid(): Boolean {
        val subjectId = configuration.subjectId ?: return false
        val itemId = configuration.itemId ?: return false
        return catalog?.containsSelection(subjectId, itemId) == true
    }

    private suspend fun reconcile(tick: Boolean) {
        val shouldFocus = foreground && studying && configuration.enabled && key != null
        if (!shouldFocus) {
            if (attempted) stop()
            return
        }
        if (blocked) return
        if (heartbeat == null) heartbeat = scope.launch {
            while (true) { delay(FocusProtocol.HEARTBEAT_MILLIS); events.send(Event.Tick) }
        }
        if (catalog == null) handleRefreshCatalog()
        if (!selectionValid()) return
        if (!attempted || tick) sendFocus()
    }

    private suspend fun sendFocus() {
        val currentKey = key ?: return
        val currentCatalog = catalog ?: return
        val subjectId = configuration.subjectId ?: return
        val itemId = configuration.itemId ?: return
        attempted = true
        try {
            when (val status = gateway.frame(currentKey, FocusProtocol.focusFrame(currentCatalog, subjectId, itemId))) {
                in 200..299 -> { accepted = true; show("正在上报专注 · 每 20 秒续报") }
                409 -> {
                    // 若前一帧已被接受，离开学习页仍需为本次会话发送 idle。
                    attempted = accepted
                    blocked = true
                    heartbeat?.cancel(); heartbeat = null
                    show("409：另一处专注正在进行或今日已结算。请稍后重新开启。")
                }
                else -> show("上报失败：HTTP $status；20 秒后重试。")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            show("上报连接失败；20 秒后重试。")
        }
    }

    private suspend fun stop() {
        heartbeat?.cancel(); heartbeat = null
        if (!attempted) return
        attempted = false
        accepted = false
        val currentKey = key ?: return
        try {
            val status = gateway.frame(currentKey, FocusProtocol.idleFrame())
            show(if (status in 200..299) "已停止专注上报。" else "停止帧未被接受；服务器将在断联 45 秒后结束。")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            show("停止帧发送失败；服务器将在断联 45 秒后结束。")
        }
    }
}
