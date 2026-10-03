package synchronice.android

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Size
import android.widget.Toast
import android.webkit.MimeTypeMap
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import synchronice.protocolclient.FilePendingQueueStore
import synchronice.protocolclient.Item
import synchronice.protocolclient.ItemGoneException
import synchronice.protocolclient.KnownAddressStore
import synchronice.protocolclient.PairingInfo
import synchronice.protocolclient.PendingItem
import synchronice.protocolclient.ProtocolClient
import synchronice.protocolclient.ProtocolRejectedException
import synchronice.protocolclient.parsePairingQr
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SynchroNiceApplication : Application() {
    internal lateinit var state: AppState
        private set

    override fun onCreate() {
        super.onCreate()
        state = AppState(this)
        if (state.hasPending()) scheduleDelivery(this)
    }
}

internal enum class Delivery { PENDING, DELIVERED, FAILED }

internal data class ChatMessage(
    val localId: Long,
    val text: String,
    val outgoing: Boolean,
    val delivery: Delivery,
    val file: Item.FileMeta? = null,
    val uri: Uri? = null,
    val progress: Int = 0,
    val preview: Bitmap? = null,
    /** Id do Item no PC (`u64`), nos recebidos. */
    val receivedId: Long? = null,
    /** Id de protocolo (UUID) do Item que o celular enviou. */
    val sentId: String? = null,
)

internal class AppState(
    context: Context,
    private val previewExecutor: Executor = Executors.newSingleThreadExecutor(),
) {
    private val appContext = context.applicationContext
    private val pairingPreferences = appContext.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    private val networkPreferences = appContext.getSharedPreferences("network", Context.MODE_PRIVATE)
    private val preferences = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var copyFormatted by mutableStateOf(preferences.getBoolean("copy-formatted", false))
        private set
    var keepPhotos by mutableStateOf(preferences.getBoolean("keep-photos", false))
        private set

    fun setKeepCameraPhotos(value: Boolean) {
        preferences.edit().putBoolean("keep-photos", value).apply()
        keepPhotos = value
    }

    fun setCopyWithPunctuation(value: Boolean) {
        preferences.edit().putBoolean("copy-formatted", value).apply()
        copyFormatted = value
    }
    private val queueFile = File(appContext.filesDir, "pending-items.txt")
    private var queueStore = FilePendingQueueStore(queueFile)
    private val outboundMutex = Mutex()
    private val receivedIds = mutableSetOf<Long>()
    private val receivedFiles = mutableMapOf<Long, Long>()
    private val inFlightFiles = ConcurrentHashMap.newKeySet<Long>()
    /** Ids de arquivos recebidos que já saíram do chat: não voltam, e o que já foi gravado é apagado. */
    private val deletedFiles = ConcurrentHashMap.newKeySet<Long>()
    /** Downloads em andamento, só na Main: quem apaga sabe se o arquivo ainda é escrito. */
    private val downloading = mutableSetOf<Long>()
    internal val files = ReceivedFiles(appContext)
    private var nextMessageId = 1L
    private var clientFor: PairingInfo? = null
    private var protocolClient: ProtocolClient? = null
    private val submitted = ArrayDeque<Long>()
    private val previewsPending = mutableListOf<CompletableDeferred<Unit>>()
    /** Um [DeliveryWorker] deste processo está entregando agora (ver [scheduleDelivery]). */
    internal val delivering = AtomicBoolean(false)

    var pairing by mutableStateOf(readPairing())
        private set
    var online by mutableStateOf(false)
        private set
    var pairingError by mutableStateOf<String?>(null)
        private set
    var connectionError by mutableStateOf<String?>(null)
        private set
    internal val messages = mutableStateListOf<ChatMessage>()
    /** Apagar tudo: bolhas fora da tela até o [deleteAll] terminar. Vive aqui para a ⚙ não perder a janela. */
    var hidden by mutableStateOf(emptySet<Long>())
    /** As bolhas esvaziadas pela janela de Desfazer aberta (vazio = nenhuma janela). */
    var window by mutableStateOf(emptySet<Long>())
    /** Quando a janela aberta acaba (`SystemClock.elapsedRealtime`): o aviso retoma a barra daqui ao voltar da ⚙. */
    var windowEnd = 0L
        private set
    private val windowScope = CoroutineScope(SupervisorJob())
    private var windowClock: Job? = null
    private val stagedPastes = mutableStateListOf<File>()
    val pasteTray: List<File> get() = stagedPastes
    val composer = TextFieldState()
    var sendingComposer by mutableStateOf(false)
        private set

    /** Devolve false quando o campo ainda precisa receber o texto do mesmo item. */
    fun pasteItem(item: ClipData.Item): Boolean {
        val uri = item.uri ?: return false
        if (sendingComposer) return false
        try {
            pasteUri(uri)
        } catch (_: Exception) {
            Toast.makeText(appContext, "Não foi possível colar este arquivo.", Toast.LENGTH_LONG).show()
        }
        // Nunca converte uma URI recusada em texto, mas preserva uma legenda explícita.
        return item.text == null
    }

    /** Copia antes de devolver o callback: a permissão do teclado é temporária. */
    fun pasteUri(uri: Uri): Boolean {
        if (!isForeignContentUri(appContext, uri)) return false
        stagedPastes.add(copyToStaging(appContext, uri) {
            val mime = appContext.contentResolver.getType(uri)
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
            "imagem-${System.currentTimeMillis()}.$extension"
        })
        return true
    }

    fun removePaste(file: File) {
        if (stagedPastes.remove(file)) file.parentFile?.deleteRecursively()
    }

    fun clearPasteTray() {
        stagedPastes.toList().forEach(::removePaste)
    }

    suspend fun sendComposer() {
        if (sendingComposer || pairing == null) return
        sendingComposer = true
        // A saída da tela não interrompe um lote que já começou a entrar nos Pendentes.
        withContext(NonCancellable) {
            try {
                val batch = pasteTray.toList()
                val text = composer.text.toString()
                for (file in batch) {
                    queueFile(file)
                    removePaste(file)
                }
                if (text.isNotBlank()) {
                    val id = addOutgoing(text)
                    queueText(id, text)
                    if (messages.any { it.localId == id && it.delivery == Delivery.FAILED }) return@withContext
                }
                composer.clearText()
            } catch (error: Exception) {
                connectionError = "Não foi possível enviar: ${error.message ?: "erro de disco"}"
            } finally {
                sendingComposer = false
            }
        }
    }

    internal val resolver = NsdInstanceResolver(appContext)
    internal val knownAddress = object : KnownAddressStore {
        override fun get(): String? = networkPreferences.getString("last-address", null)

        override fun save(ip: String) {
            networkPreferences.edit().putString("last-address", ip).apply()
        }
    }

    init {
        queueStore.load().forEach { pending ->
            val label = when (pending) {
                is PendingItem.Text -> pending.text
                is PendingItem.File -> pending.name
                is PendingItem.Delete -> return@forEach
            }
            val id = nextMessageId++
            messages.add(if (pending is PendingItem.File) outgoingFile(id, pending, decodeNow = false)
                else ChatMessage(id, label, true, Delivery.PENDING, sentId = pending.id))
            if (pending is PendingItem.File && fileMimeType(pending.name).startsWith("image/")) {
                val previewDone = CompletableDeferred<Unit>()
                previewsPending.add(previewDone)
                schedulePreviewDecode(id, File(pending.path), previewDone)
            }
            submitted.addLast(id)
        }
    }

    private fun schedulePreviewDecode(id: Long, file: File, done: CompletableDeferred<Unit>) {
        previewExecutor.execute {
            try {
                val preview = decodePreview(ImageDecoder.createSource(file))
                kotlinx.coroutines.runBlocking(Dispatchers.Main.immediate) {
                    val index = messages.indexOfFirst { it.localId == id }
                    if (index >= 0) messages[index] = messages[index].copy(preview = preview)
                }
            } finally {
                done.complete(Unit)
            }
        }
    }

    fun acceptQr(json: String): Boolean {
        val newPair = try {
            parsePairingQr(json)
        } catch (_: IllegalArgumentException) {
            pairingError = "QR inválido. Aponte para o código exibido pelo SynchroNice no PC."
            return false
        }

        if (pairingPreferences.getBoolean("discard-pending", false)) {
            try {
                queueStore.clear()
            } catch (error: IOException) {
                pairingError = "Não foi possível limpar os Pendentes do Par anterior: ${error.message}"
                return false
            }
        }
        pairingPreferences.edit()
            .also { editor ->
                if (newPair != pairing) pairingPreferences.all.keys
                    .filter { it.startsWith("received-file:") }.forEach(editor::remove)
            }
            .remove("discard-pending")
            .putString("instance-name", newPair.instanceName)
            .putInt("port", newPair.port)
            .putString("token", newPair.token)
            .apply()
        // O IP do QR só semeia o last-address; o mDNS, se resolver, sobrescreve.
        newPair.ip?.let(knownAddress::save)
        pairing = newPair
        pairingError = null
        connectionError = null
        online = false
        messages.clear()
        receivedIds.clear()
        receivedFiles.clear()
        inFlightFiles.clear()
        deletedFiles.clear()
        windowClock?.cancel()
        hidden = emptySet()
        window = emptySet()
        protocolClient = null
        clientFor = null
        return true
    }

    suspend fun unpair(expected: PairingInfo? = pairing) {
        val currentPair = expected
        try {
            withContext(Dispatchers.IO + NonCancellable) {
                outboundMutex.withLock {
                    if (withContext(Dispatchers.Main.immediate) { pairing != currentPair }) return@withLock
                    val receipts = pairingPreferences.all.filterKeys { it.startsWith("received-file:") }
                    val removal = pairingPreferences.edit()
                    removal.clear()
                    removal.putBoolean("discard-pending", true)
                    if (currentPair != null && !removal.commit()) {
                        // SharedPreferences also changes its memory cache when commit fails.
                        pairingPreferences.edit()
                            .also { editor -> receipts.forEach { (key, uri) -> editor.putString(key, uri as String) } }
                            .remove("discard-pending")
                            .putString("instance-name", currentPair.instanceName)
                            .putInt("port", currentPair.port)
                            .putString("token", currentPair.token)
                            .apply()
                        throw IOException("não foi possível apagar o Par")
                    }
                    try {
                        queueStore.clear()
                        pairingPreferences.edit().remove("discard-pending").apply()
                    } finally {
                        withContext(Dispatchers.Main.immediate) {
                            protocolClient?.closeListen()
                            networkPreferences.edit().remove("last-address").apply()
                            protocolClient = null
                            clientFor = null
                            pairing = null
                            online = false
                            pairingError = null
                            connectionError = null
                            messages.clear()
                            submitted.clear()
                            receivedIds.clear()
                            receivedFiles.clear()
                            inFlightFiles.clear()
                            deletedFiles.clear()
                            windowClock?.cancel()
                            hidden = emptySet()
                            window = emptySet()
                        }
                    }
                }
            }
        } catch (error: IOException) {
            withContext(Dispatchers.Main.immediate) {
                connectionError = "Não foi possível desparear: ${error.message ?: "erro de disco"}"
            }
        }
    }

    /**
     * "Mudar pasta…" é também a intervenção que um recebimento preso pede (SYN-014, critério 3:
     * marcador que o provedor escondeu, Pasta sem permissão): o que ainda não tinha conteúdo
     * completo recomeça na Pasta escolhida. O parcial antigo fica onde estava, com o marcador.
     */
    fun chooseFolder(uri: Uri) {
        files.chooseFolder(uri)
        val editor = pairingPreferences.edit()
        pairingPreferences.all.forEach { (key, value) ->
            val unfinished = value is String && (value.startsWith("creating:") || value.startsWith("writing:"))
            if (key.startsWith("received-file:") && unfinished && key.substringAfterLast(':').toLongOrNull() !in inFlightFiles) {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    @Synchronized
    fun client(): ProtocolClient? {
        val currentPair = pairing ?: return null
        if (protocolClient == null || clientFor != currentPair) {
            protocolClient = ProtocolClient(currentPair, resolver, knownAddress, queueStore)
            clientFor = currentPair
        }
        return protocolClient
    }

    fun addOutgoing(text: String): Long {
        val id = nextMessageId++
        messages.add(ChatMessage(id, text, true, Delivery.PENDING))
        connectionError = null
        return id
    }

    suspend fun queueText(id: Long, text: String) {
        val currentClient = client() ?: return
        try {
            val queued = withContext(Dispatchers.IO) {
                outboundMutex.withLock {
                    if (currentClient !== protocolClient) throw IOException("Par alterado antes de guardar a mensagem")
                    // Apagar tudo chegou antes do enfileiramento: a bolha saiu, o texto não sai pela rede.
                    if (withContext(Dispatchers.Main.immediate) { messages.none { it.localId == id } }) return@withLock false
                    val sentId = currentClient.enqueueTextItem(text)
                    withContext(Dispatchers.Main.immediate) {
                        val index = messages.indexOfFirst { it.localId == id }
                        if (index >= 0) messages[index] = messages[index].copy(sentId = sentId)
                        submitted.addLast(id)
                    }
                    true
                }
            }
            if (queued) scheduleDelivery(appContext)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            setDelivery(id, Delivery.FAILED)
            connectionError = "Não foi possível guardar a mensagem: ${error.message ?: "erro de disco"}"
        }
    }

    suspend fun queueFile(file: File) {
        val currentClient = client() ?: return
        val stored = withContext(Dispatchers.IO) {
            outboundMutex.withLock {
                if (currentClient !== protocolClient) throw IOException("Par alterado antes de guardar o arquivo")
                val stored = currentClient.enqueueFile(file)
                if (stored) {
                    val pending = queueStore.load().last() as PendingItem.File
                    val message = outgoingFile(0, pending)
                    withContext(Dispatchers.Main.immediate) {
                    val id = nextMessageId++
                    messages.add(message.copy(localId = id))
                    submitted.addLast(id)
                    }
                }
                stored
            }
        }
        if (!stored) throw IOException("arquivo não disponível")
        scheduleDelivery(appContext)
    }

    private fun outgoingFile(id: Long, pending: PendingItem.File, decodeNow: Boolean = true): ChatMessage {
        val file = File(pending.path)
        return ChatMessage(
            id, pending.name, true, Delivery.PENDING,
            file = Item.FileMeta(id, 0, pending.name, file.length()),
            preview = if (decodeNow && fileMimeType(pending.name).startsWith("image/"))
                decodePreview(ImageDecoder.createSource(file)) else null,
            sentId = pending.id,
        )
    }

    fun hasPending(): Boolean = queueStore.load().isNotEmpty()

    fun hasLargePending(): Boolean = queueStore.load().filterIsInstance<PendingItem.File>()
        .any { File(it.path).length() >= LARGE_FILE_BYTES }

    fun shouldAskNotificationPermission(): Boolean {
        if (networkPreferences.getBoolean("notification-permission-asked", false)) return false
        networkPreferences.edit().putBoolean("notification-permission-asked", true).apply()
        return true
    }

    suspend fun retryPending(
        onFileProgress: (Int) -> Unit = {},
        onFileStart: (Long) -> Unit = {},
    ) {
        // ponytail: delivery waits for every restored preview (2048 px software decode, ~150-300+ ms each,
        // also on worker-only wake-ups); wait per item, or skip previews outside the UI, if the delay shows.
        previewsPending.forEach { it.await() }
        val currentPair = pairing
        val currentClient = client() ?: run { unpair(); return }
        try {
            outboundMutex.withLock {
                if (currentClient !== protocolClient) return@withLock
                try {
                    currentClient.retryPending(onFileProgress, onFileStart)
                } finally {
                    // Uma exclusão na fila ocupa posição, mas nunca teve bolha em `submitted`.
                    val remaining = queueStore.load().count { it !is PendingItem.Delete }
                    withContext(Dispatchers.Main.immediate) {
                        reconcilePendingDelivery(messages, submitted, remaining)
                    }
                }
            }
        } catch (error: ProtocolRejectedException) {
            if (error.code != 401) {
                // O PC responde 400 a um upload parado e 500 a erro de disco: o worker tenta de novo, na ordem.
                withContext(Dispatchers.Main.immediate) {
                    connectionError = "O PC recusou um Pendente (HTTP ${error.code}); tentando de novo."
                }
                throw error
            }
            unpair(currentPair)
        }
    }

    fun receive(item: Item.Text) {
        if (receivedIds.add(item.id)) {
            messages.add(ChatMessage(nextMessageId++, item.text, false, Delivery.DELIVERED, receivedId = item.id))
            connectionError = null
        }
    }

    /**
     * Tira da Sessão as bolhas escolhidas por [selected] e apaga os arquivos que o app gravou para
     * elas; o que ainda está baixando é apagado quando o download termina ou falha (ver
     * [receiveFile]). [announced] são ids de arquivos de uma exclusão do PC: os que o `listen` já
     * anunciou, mas ainda sem bolha, também são marcados, para a bolha nunca nascer. Devolve as bolhas tiradas.
     */
    private suspend fun dropMessages(announced: Set<Long> = emptySet(), selected: (ChatMessage) -> Boolean): List<ChatMessage> {
        val token = pairing?.token
        val (dropped, finished) = withContext(Dispatchers.Main.immediate) {
            val gone = messages.filter(selected)
            val ids = gone.map { it.localId }.toSet()
            messages.removeAll { it.localId in ids }
            submitted.removeAll { it in ids }
            val received = gone.filter { !it.outgoing && it.file != null }
            received.forEach { deletedFiles.add(it.file!!.id) }
            announced.filterTo(deletedFiles) { it in inFlightFiles }
            gone to received.filter { it.file!!.id !in downloading }
        }
        finished.forEach { message ->
            val item = message.file!!
            val key = "received-file:$token:${item.id}"
            val receipt = pairingPreferences.getString(key, null)
            // Completo (a bolha ou o recibo guardam a URI final): só com o tamanho do Item. O parcial
            // interrompido só está no recibo `writing:` e não tem tamanho para conferir.
            val saved = message.uri?.let { it to item.size } ?: when {
                receipt == null -> null
                receipt.startsWith("writing:") -> Uri.parse(receipt.removePrefix("writing:")) to null
                receipt.startsWith("renaming:") -> Uri.parse(receipt.removePrefix("renaming:")) to item.size
                receipt.startsWith("content:") -> Uri.parse(receipt) to item.size
                else -> null
            }
            saved?.let { (uri, size) -> files.delete(uri, size) }
            if (receipt != null) pairingPreferences.edit().remove(key).apply()
        }
        return dropped
    }

    /**
     * O fim da janela de Desfazer: [localIds] são as bolhas que ela esvaziou. A exclusão é guardada na
     * fila antes de qualquer bolha, Pendente ou arquivo sair: se o disco falhar, nada se perdeu e a
     * conversa volta à tela.
     */
    suspend fun deleteAll(localIds: Set<Long> = messages.map { it.localId }.toSet()) {
        val currentClient = client() ?: return
        val queued = try {
            withContext(Dispatchers.IO + NonCancellable) {
                outboundMutex.withLock {
                    if (currentClient !== protocolClient) return@withLock false
                    val gone = withContext(Dispatchers.Main.immediate) { messages.filter { it.localId in localIds } }
                    if (gone.isEmpty()) return@withLock false
                    currentClient.enqueueDelete(gone.mapNotNull { it.receivedId }, gone.mapNotNull { it.sentId })
                    dropMessages { it.localId in localIds }
                    true
                }
            }
        } catch (error: IOException) {
            connectionError = "Não foi possível apagar a conversa: ${error.message ?: "erro de disco"}"
            false
        }
        if (queued) scheduleDelivery(appContext)
    }

    /** Abre a janela de Desfazer: esvazia o chat na tela e só apaga de verdade quando [windowMs] passa. */
    fun beginClear(windowMs: Long = CLEAR_WINDOW_MS.toLong()) {
        windowClock?.cancel()
        if (window.isNotEmpty()) commitClear()
        val ids = messages.filter { it.localId !in hidden }.map { it.localId }.toSet()
        if (ids.isEmpty()) return
        window = ids
        hidden = hidden + ids
        windowEnd = SystemClock.elapsedRealtime() + windowMs
        // O relógio é do AppState, não da tela: abrir a ⚙ não o pausa nem o cancela.
        windowClock = windowScope.launch(Dispatchers.Main.immediate) {
            delay(windowMs)
            commitClear()
        }
    }

    fun undoClear() {
        windowClock?.cancel()
        hidden = hidden - window
        window = emptySet()
    }

    private fun commitClear() {
        windowClock?.cancel()
        val ids = window
        window = emptySet()
        windowScope.launch(Dispatchers.Main.immediate) {
            deleteAll(ids)
            hidden = hidden - ids
        }
    }

    /** Uma exclusão vinda do PC: o ack só sai depois de a bolha, o arquivo e o Pendente listados saírem. */
    private suspend fun applyDelete(client: ProtocolClient, delete: Item.Delete) {
        outboundMutex.withLock {
            if (client !== protocolClient) return@withLock
            dropMessages(delete.doPc.toSet()) { it.receivedId in delete.doPc || it.sentId in delete.doCelular }
            client.discardPending(delete.doCelular)
            client.acknowledgeDelete(delete)
        }
    }

    fun updateOnline(value: Boolean) {
        val cameBack = value && !online
        online = value
        // Presença é a escuta chegando ao PC: hora de entregar o Pendente, sem esperar o backoff.
        if (cameBack && hasPending()) scheduleDelivery(appContext)
    }

    suspend fun listen(currentPair: PairingInfo, client: ProtocolClient, dispatcher: CoroutineDispatcher = Dispatchers.IO) = withContext(dispatcher) {
        while (isActive) {
            try {
                retryReceivedReceipts(currentPair, client)
                val items = kotlinx.coroutines.runInterruptible { client.listen() }
                withContext(Dispatchers.Main.immediate) {
                    items.filterIsInstance<Item.Text>().forEach(::receive)
                }
                items.filterIsInstance<Item.Text>().forEach { client.acknowledgeText(it) }
                items.filterIsInstance<Item.Delete>().forEach { applyDelete(client, it) }
                var startedDownload = false
                for (file in items.filterIsInstance<Item.FileMeta>()) {
                    val isNew = file.id !in deletedFiles && inFlightFiles.add(file.id)
                    if (!isNew) continue
                    startedDownload = true
                    launch {
                        try {
                            receiveFile(file, client)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            // receiveFile already recorded the failure in connectionError and delivery.
                            // Keep the id in flight past the loop's 1.5 s empty-batch wait, so a fast,
                            // repeating failure doesn't spin the listen loop hot on the same re-announced file.
                            delay(2_000)
                        }
                    }.invokeOnCompletion {
                        // Runs on any completion path, including a job cancelled before its body ever
                        // ran: a cancelled listen loop (ON_PAUSE) must still free the id, or the
                        // relaunched loop ignores the re-announced file forever.
                        inFlightFiles.remove(file.id)
                    }
                }
                if (!startedDownload && items.none { it is Item.Text || it is Item.Delete }) delay(1_500)
            } catch (error: CancellationException) {
                throw error
            } catch (_: SocketTimeoutException) {
                // Reopen the idle long-poll so mDNS can resolve a changed PC address.
            } catch (error: ProtocolRejectedException) {
                if (error.code == 401) {
                    unpair(currentPair)
                    break
                }
                withContext(Dispatchers.Main.immediate) { updateOnline(false) }
                delay(1_000)
            } catch (error: IOException) {
                withContext(Dispatchers.Main.immediate) { updateOnline(false) }
                delay(1_000)
            } catch (_: Exception) {
                withContext(Dispatchers.Main.immediate) { updateOnline(false) }
                delay(1_500)
            }
        }
    }

    private fun removeReceipt(key: String) {
        val receipt = pairingPreferences.getString(key, null)
        if (!pairingPreferences.edit().remove(key).commit()) {
            // commit(false) still changes the in-memory preferences; preserve the retry there too.
            pairingPreferences.edit().putString(key, receipt).commit()
            throw IOException("não foi possível apagar o recibo de recebimento")
        }
    }

    private fun dropStaleReceipt(itemId: Long, key: String) {
        receivedFiles[itemId]?.let { localId ->
            val index = messages.indexOfFirst { it.localId == localId }
            if (index >= 0) messages[index] = messages[index].copy(delivery = Delivery.FAILED, uri = null)
        }
        removeReceipt(key)
    }

    private suspend fun retryReceivedReceipts(currentPair: PairingInfo, client: ProtocolClient) = withContext(Dispatchers.Main.immediate) {
        if (client !== protocolClient || pairing != currentPair) return@withContext
        val prefix = "received-file:${currentPair.token}:"
        val receipts = pairingPreferences.all.filter { (key, value) ->
            key.startsWith(prefix) && value is String && value.startsWith("content:")
        }
        for ((key, value) in receipts) {
            if (client !== protocolClient || pairing != currentPair) return@withContext
            val itemId = key.removePrefix(prefix).toLong()
            if (itemId in inFlightFiles) continue
            try {
                withContext(Dispatchers.IO) {
                    kotlinx.coroutines.runInterruptible {
                        files.publish(Uri.parse(value as String))
                        client.acknowledgeFile(itemId)
                    }
                }
                if (client !== protocolClient || pairing != currentPair) return@withContext
                receivedFiles[itemId]?.let { setDelivery(it, Delivery.DELIVERED) }
                removeReceipt(key)
                connectionError = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: FileGoneException) {
                try {
                    dropStaleReceipt(itemId, key)
                } catch (cleanup: IOException) {
                    connectionError = "Não foi possível concluir o recebimento: ${cleanup.message}"
                }
            } catch (error: Exception) {
                // A receipt that keeps failing stays queued for the next attempt; it must not stop listening.
                connectionError = "Não foi possível concluir o recebimento: ${error.message}"
            }
        }
    }

    suspend fun receiveFile(item: Item.FileMeta, currentClient: ProtocolClient) = withContext(Dispatchers.Main.immediate) {
        if (currentClient !== protocolClient) return@withContext
        // A exclusão chegou entre o anúncio e este ponto: a bolha não nasce e nada é baixado.
        if (item.id in deletedFiles) return@withContext
        val receiptKey = "received-file:${pairing!!.token}:${item.id}"
        val receipt = pairingPreferences.getString(receiptKey, null)
        val incompleteUri = receipt?.takeIf { it.startsWith("writing:") }?.removePrefix("writing:")?.let(Uri::parse)
        val creationIntent = receipt?.takeIf { it.startsWith("creating:") }?.removePrefix("creating:")
        val renamingUri = receipt?.takeIf { it.startsWith("renaming:") }?.removePrefix("renaming:")?.let(Uri::parse)
        val persistedUri = receipt?.takeIf { it.startsWith("content:") }?.let(Uri::parse)
        val id = receivedFiles.getOrPut(item.id) {
            val id = nextMessageId++
            messages.add(ChatMessage(id, item.name, false, Delivery.PENDING, file = item, uri = persistedUri, receivedId = item.id))
            id
        }
        fun update(change: (ChatMessage) -> ChatMessage) {
            if (currentClient !== protocolClient) throw IOException("Par alterado durante o download")
            val index = messages.indexOfFirst { it.localId == id }
            if (index >= 0) messages[index] = change(messages[index])
        }
        fun saveReceipt(value: String) {
            if (currentClient !== protocolClient) throw IOException("Par alterado durante o download")
            val previous = pairingPreferences.getString(receiptKey, null)
            if (!pairingPreferences.edit().putString(receiptKey, value).commit()) {
                pairingPreferences.edit().putString(receiptKey, previous).commit()
                throw IOException("não foi possível guardar o recebimento")
            }
        }
        var acknowledged = false
        val savedUri = messages.firstOrNull { it.localId == id }?.uri
        // Onde o app gravou: a bolha pode sair antes do fim, e o arquivo ainda precisa ser achado.
        var location: Uri? = savedUri ?: renamingUri ?: incompleteUri
        // O conteúdo está completo (com ou sem o ack aceito): só então o tamanho do Item vale como proteção.
        var complete = savedUri != null || renamingUri != null
        update { it.copy(delivery = Delivery.PENDING, progress = 0) }
        downloading.add(item.id)
        try {
            withContext(Dispatchers.IO) {
                kotlinx.coroutines.runInterruptible {
                    var lastProgress = -1
                    if (savedUri != null) {
                        try {
                            files.publish(savedUri)
                        } catch (error: FileGoneException) {
                            kotlinx.coroutines.runBlocking(Dispatchers.Main.immediate) { dropStaleReceipt(item.id, receiptKey) }
                            throw error
                        }
                        currentClient.acknowledgeFile(item.id)
                    }
                    else if (renamingUri != null) {
                        // O conteúdo já estava completo quando o rename começou: só falta terminar e confirmar.
                        val saved = files.finishRenaming(renamingUri, item.name)
                        location = saved
                        kotlinx.coroutines.runBlocking(Dispatchers.Main.immediate) {
                            if (saved != null) saveReceipt(saved.toString())
                            update { it.copy(uri = saved) }
                        }
                        currentClient.acknowledgeFile(item.id)
                    }
                    else files.receive(currentClient, item, existingUri = incompleteUri, creationIntent = creationIntent, onCreating = { intent ->
                        kotlinx.coroutines.runBlocking(Dispatchers.Main.immediate) {
                            saveReceipt("creating:$intent")
                        }
                    }, onCreated = { uri ->
                        location = uri
                        kotlinx.coroutines.runBlocking(Dispatchers.Main.immediate) {
                            saveReceipt("writing:$uri")
                        }
                    }, onRenaming = { uri ->
                        location = uri
                        complete = true
                        kotlinx.coroutines.runBlocking(Dispatchers.Main.immediate) {
                            saveReceipt("renaming:$uri")
                        }
                    }, onSaved = { uri ->
                        location = uri
                        complete = true
                        kotlinx.coroutines.runBlocking(Dispatchers.Main.immediate) {
                            saveReceipt(uri.toString())
                            update { it.copy(uri = uri) }
                        }
                    }, onProgress = { progress ->
                        if (Thread.currentThread().isInterrupted) throw IOException("download interrompido")
                        if (progress != lastProgress) {
                            lastProgress = progress
                            kotlinx.coroutines.runBlocking(Dispatchers.Main.immediate) {
                                update { it.copy(progress = progress.coerceIn(0, 100)) }
                            }
                        }
                    })
                    acknowledged = true
                    // A successful ACK delivers the Item even if local cleanup needs another attempt.
                    kotlinx.coroutines.runBlocking(Dispatchers.Main.immediate) {
                        update { it.copy(delivery = Delivery.DELIVERED, progress = 100) }
                        removeReceipt(receiptKey)
                    }
                }
            }
            update { it.copy(delivery = Delivery.DELIVERED, progress = 100) }
            connectionError = null
        } catch (error: ItemGoneException) {
            update { it.copy(delivery = if (it.uri != null) Delivery.DELIVERED else Delivery.FAILED) }
        } catch (error: Exception) {
            if (currentClient === protocolClient) {
                update { it.copy(delivery = if (acknowledged) Delivery.DELIVERED else Delivery.FAILED) }
                if (error !is CancellationException) connectionError = "Não foi possível receber ${item.name}: ${error.message}"
            }
            throw error
        } finally {
            downloading.remove(item.id)
            if (item.id in deletedFiles) {
                // A bolha saiu durante o download: o arquivo vai junto. Só o parcial não tem o tamanho do Item.
                pairingPreferences.edit().remove(receiptKey).apply()
                location?.let { uri ->
                    withContext(Dispatchers.IO + NonCancellable) { files.delete(uri, if (complete) item.size else null) }
                }
            }
        }
    }

    private fun setDelivery(id: Long, delivery: Delivery) {
        val index = messages.indexOfFirst { it.localId == id }
        if (index >= 0) messages[index] = messages[index].copy(delivery = delivery)
    }

    private fun readPairing(): PairingInfo? {
        val name = pairingPreferences.getString("instance-name", null) ?: return null
        val port = pairingPreferences.getInt("port", 0)
        val token = pairingPreferences.getString("token", null) ?: return null
        return if (port in 1..65535) PairingInfo(name, port, token) else null
    }
}

internal fun reconcilePendingDelivery(
    messages: MutableList<ChatMessage>,
    submitted: ArrayDeque<Long>,
    remainingTexts: Int,
) {
    while (submitted.size > remainingTexts) {
        val deliveredId = submitted.removeFirst()
        val index = messages.indexOfFirst { it.localId == deliveredId }
        if (index >= 0) messages[index] = messages[index].copy(delivery = Delivery.DELIVERED)
    }
}

class MainActivity : ComponentActivity() {
    private var photoName: String? = null
    private val state get() = (application as SynchroNiceApplication).state
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { confirmed ->
        val file = photoName?.let { File(filesDir, "camera/$it") } ?: return@registerForActivityResult
        photoName = null
        lifecycleScope.launch(NonCancellable) {
            try {
                if (confirmed) {
                    if (file.length() == 0L) throw IOException("a câmera não gravou a foto")
                    if (state.keepPhotos) try {
                        withContext(Dispatchers.IO) { state.files.savePhoto(file) }
                    } catch (error: Exception) {
                        Toast.makeText(
                            this@MainActivity,
                            "A foto será enviada, mas não foi possível guardá-la na Pasta: ${error.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    if (state.pairing == null) throw IOException("pareie com o PC antes de enviar")
                    state.queueFile(file)
                }
            } catch (error: Exception) {
                Toast.makeText(this@MainActivity, "Não foi possível concluir a foto: ${error.message}", Toast.LENGTH_LONG).show()
            } finally {
                // A fila guarda a própria cópia: a temporária sai em qualquer caminho (critério 5 do SYN-017).
                withContext(Dispatchers.IO) { file.delete() }
            }
        }
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCamera()
        else Toast.makeText(this, "Permita a câmera para tirar uma foto.", Toast.LENGTH_LONG).show()
    }

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        var file: File? = null
        try {
            val directory = File(filesDir, "camera").apply { mkdirs() }
            file = File.createTempFile("Foto-", ".jpg", directory)
            photoName = file.name
            takePicture.launch(FileProvider.getUriForFile(this, "$packageName.camera", file))
        } catch (error: Exception) {
            file?.delete()
            photoName = null
            Toast.makeText(this, "Não foi possível abrir a câmera: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("camera-photo", photoName)
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        photoName = savedInstanceState?.getString("camera-photo")
        val state = (application as SynchroNiceApplication).state
        setContent {
            BackHandler(enabled = state.pasteTray.isNotEmpty()) {
                if (!state.sendingComposer) state.clearPasteTray()
            }
            MaterialTheme {
                Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    var showSettings by remember(state.pairing) { mutableStateOf(false) }
                    if (state.pairing == null) PairingScreen(state)
                    else if (showSettings) SettingsScreen(state, onBack = { showSettings = false })
                    else ChatScreen(state, onSettings = { showSettings = true }, onCamera = ::openCamera)
                }
            }
        }
    }
}

@Composable
private fun PairingScreen(state: AppState) {
    val context = LocalContext.current
    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraGranted = it
    }
    LaunchedEffect(Unit) {
        if (!cameraGranted) permissionRequest.launch(Manifest.permission.CAMERA)
    }

    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("SynchroNice", style = MaterialTheme.typography.headlineSmall)
        Text("Escaneie o QR exibido no PC para parear.", Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
        if (cameraGranted) {
            QrCameraPreview(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 16.dp),
                onQr = state::acceptQr,
            )
        } else {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("A câmera é necessária para ler o QR.", textAlign = TextAlign.Center)
                Button(onClick = { permissionRequest.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Permitir câmera")
                }
            }
        }
        state.pairingError?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatScreen(state: AppState, onSettings: () -> Unit, onCamera: () -> Unit) {
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            state.shouldAskNotificationPermission()
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val draft = state.composer
    var viewedImage by remember { mutableStateOf<ChatMessage?>(null) }
    val messages = state.messages.filter { it.localId !in state.hidden }
    ConnectionLifecycle(state)

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    val visibleIds = messages.map { it.localId }
    LaunchedEffect(visibleIds, viewedImage) {
        if (viewedImage?.let { it.localId !in visibleIds } == true) viewedImage = null
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("SynchroNice", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.online) "Na rede" else "Fora da rede", modifier = Modifier.testTag("presence"), color = if (state.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                if (messages.isNotEmpty()) {
                    TextButton(
                        onClick = { state.beginClear() },
                        modifier = Modifier.testTag("clear-all").semantics { contentDescription = "Apagar tudo" },
                    ) { Text("🗑", style = MaterialTheme.typography.titleLarge) }
                }
                TextButton(onClick = onSettings, modifier = Modifier.testTag("settings").semantics { contentDescription = "Configurações" }) {
                    Text("⚙", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        state.connectionError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("O chat está vazio. Digite uma mensagem para enviar ao PC.", textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.localId }) { message ->
                    MessageBubble(message, state.copyFormatted, onImage = { viewedImage = it })
                }
            }
        }
        if (state.pasteTray.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp).testTag("paste-tray")) {
                items(state.pasteTray, key = { it.absolutePath }) { file ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(file.name, Modifier.weight(1f), maxLines = 2)
                        TextButton(
                            enabled = !state.sendingComposer,
                            onClick = { state.removePaste(file) },
                            modifier = Modifier.testTag("remove-paste-${file.parentFile?.name}")
                                .semantics { contentDescription = "Remover ${file.name}" },
                        ) { Text("✕") }
                    }
                }
            }
        }
        if (state.window.isNotEmpty()) {
            val cleared = state.messages.filter { it.localId in state.window }
            key(state.window) {
                ClearedNotice(
                    items = cleared.size,
                    files = cleared.count { it.file != null },
                    windowEnd = state.windowEnd,
                    onUndo = state::undoClear,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCamera, modifier = Modifier.testTag("camera").semantics { contentDescription = "Tirar foto" }) {
                Text("📷")
            }
            OutlinedTextField(
                state = draft,
                readOnly = state.sendingComposer,
                modifier = Modifier.weight(1f).testTag("composer").contentReceiver { content ->
                    content.consume(state::pasteItem)
                },
                placeholder = { Text("Mensagem") },
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
            )
            Button(
                enabled = !state.sendingComposer && (draft.text.isNotBlank() || state.pasteTray.isNotEmpty()),
                modifier = Modifier.testTag("send"),
                onClick = {
                    scope.launch { state.sendComposer() }
                },
            ) { Text("Enviar") }
        }
    }
    viewedImage?.let { ImageViewer(it, onClose = { viewedImage = null }) }
}

private const val CLEAR_WINDOW_MS = 5_000

/**
 * O aviso do Apagar tudo: nada é apagado nem sai pela rede enquanto a barra esvazia. Só desenha o
 * prazo; quem o conta é o [AppState] (`beginClear`), que segue contando com o chat fora da tela.
 */
@Composable
private fun ClearedNotice(items: Int, files: Int, windowEnd: Long, onUndo: () -> Unit) {
    val left = remember { (windowEnd - SystemClock.elapsedRealtime()).coerceIn(0L, CLEAR_WINDOW_MS.toLong()) }
    var target by remember { mutableStateOf(left / CLEAR_WINDOW_MS.toFloat()) }
    val remaining by animateFloatAsState(target, tween(left.toInt(), easing = LinearEasing), label = "clear-window")
    LaunchedEffect(Unit) { target = 0f }
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("clear-notice")) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Conversa apagada · $items ${if (items == 1) "Item" else "Itens"} e $files ${if (files == 1) "arquivo" else "arquivos"}",
                Modifier.weight(1f),
            )
            TextButton(onClick = onUndo, modifier = Modifier.testTag("undo-clear")) { Text("Desfazer") }
        }
        LinearProgressIndicator(progress = { remaining }, modifier = Modifier.fillMaxWidth().testTag("clear-bar"))
    }
}

@Composable
private fun SettingsScreen(state: AppState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf(state.files.folder()) }
    var folderError by remember { mutableStateOf<String?>(null) }
    val chooseFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                state.chooseFolder(uri)
                folder = uri
                folderError = null
            } catch (error: Exception) {
                folderError = "Não foi possível mudar a Pasta: ${error.message}"
            }
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("settings-back")) { Text("Voltar") }
        Text("Configurações", style = MaterialTheme.typography.headlineSmall)
        Text(folder?.lastPathSegment ?: "Download/SynchroNice", modifier = Modifier.padding(top = 16.dp))
        TextButton(onClick = { chooseFolder.launch(folder) }, modifier = Modifier.testTag("change-folder")) { Text("Mudar pasta…") }
        folderError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(
            Modifier.fillMaxWidth().testTag("copy-formatted").toggleable(
                value = state.copyFormatted,
                role = Role.Checkbox,
                onValueChange = state::setCopyWithPunctuation,
            ).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = state.copyFormatted, onCheckedChange = null)
            Text("Copiar com pontuação", Modifier.padding(start = 8.dp))
        }
        Row(
            Modifier.fillMaxWidth().testTag("keep-photos").toggleable(
                value = state.keepPhotos,
                role = Role.Checkbox,
                onValueChange = state::setKeepCameraPhotos,
            ).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = state.keepPhotos, onCheckedChange = null)
            Text("Guardar fotos tiradas", Modifier.padding(start = 8.dp))
        }
        Button(onClick = { scope.launch { state.unpair() } }, modifier = Modifier.testTag("unpair").padding(top = 24.dp)) {
            Text("Desparear")
        }
        state.connectionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, copyFormatted: Boolean, onImage: (ChatMessage) -> Unit) {
    val context = LocalContext.current
    val file = message.file
    val uri = message.uri
    val isImage = file != null && fileMimeType(file.name).startsWith("image/")
    val animatedProgress by animateFloatAsState(message.progress / 100f, label = "download")
    var thumbnail by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(uri) {
        if (uri != null && file != null && fileMimeType(file.name).startsWith("image/")) {
            thumbnail = withContext(Dispatchers.IO) {
                try { context.contentResolver.loadThumbnail(uri, Size(480, 480), null) }
                catch (_: IOException) { null }
                catch (_: SecurityException) { null }
            }
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.testTag("bubble-${message.localId}-${if (message.outgoing) "outgoing" else "incoming"}-${message.delivery.name.lowercase()}").sizeIn(maxWidth = 320.dp).clickable(
                // Arquivo ainda baixando (ou enviado, sem prévia) não tem o que abrir.
                enabled = file == null || uri != null || (isImage && message.preview != null),
                onClickLabel = if (file == null) "Copiar texto inteiro" else if (isImage) "Abrir imagem" else "Abrir arquivo",
            ) {
                if (file == null) copyText(context, message.text)
                else if (isImage) onImage(message)
                else try {
                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, fileMimeType(message.text))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                } catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(context, "Nenhum app pode abrir este arquivo.", Toast.LENGTH_LONG).show()
                } catch (_: SecurityException) {
                    Toast.makeText(context, "Não foi possível acessar este arquivo.", Toast.LENGTH_LONG).show()
                }
            },
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (message.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (file == null) CopyableText(message.text, copyFormatted)
                else {
                    Text(message.text)
                    (message.preview ?: thumbnail)?.let { Image(it.asImageBitmap(), contentDescription = file.name, modifier = Modifier.sizeIn(maxWidth = 240.dp, maxHeight = 240.dp)) }
                    Text("${file.name.substringAfterLast('.', fileMimeType(file.name)).uppercase()} · ${android.text.format.Formatter.formatFileSize(context, file.size)}", style = MaterialTheme.typography.labelSmall)
                    if (!message.outgoing && message.delivery == Delivery.PENDING) {
                        LinearProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxWidth())
                        Text("Recebendo ${message.progress}%", style = MaterialTheme.typography.labelSmall)
                    } else if (!message.outgoing && message.delivery == Delivery.FAILED) {
                        Text("Falha ao receber; tentando novamente", color = MaterialTheme.colorScheme.error)
                    }
                }
                if (message.outgoing) {
                    val label = when (message.delivery) {
                        Delivery.PENDING -> "Pendente"
                        Delivery.DELIVERED -> "Entregue"
                        Delivery.FAILED -> "Falha"
                    }
                    Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))
                }
            }
        }
    }
}

@Composable
private fun ConnectionLifecycle(state: AppState) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pairing = state.pairing
    DisposableEffect(lifecycle, pairing) {
        val client = state.client()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.updateOnline(false)
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                state.updateOnline(false)
                client?.closeListen()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            state.updateOnline(false)
            client?.closeListen()
        }
    }
    LaunchedEffect(lifecycle, pairing) {
        val currentPair = pairing ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            state.updateOnline(false)
            try {
                val client = state.client() ?: return@repeatOnLifecycle
                coroutineScope {
                    launch(Dispatchers.IO) {
                        while (isActive) {
                            try {
                                val address = state.resolver.resolve(currentPair.instanceName)
                                if (address != null) state.knownAddress.save(address)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                // DNS lookup does not define the /listen presence.
                            }
                            delay(4_000)
                        }
                    }
                    launch(Dispatchers.IO) {
                        while (isActive) {
                            try {
                                val active = client.isListening()
                                withContext(Dispatchers.Main.immediate) { state.updateOnline(active) }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: ProtocolRejectedException) {
                                if (error.code == 401) {
                                    state.unpair(currentPair)
                                    break
                                }
                                withContext(Dispatchers.Main.immediate) { state.updateOnline(false) }
                            } catch (_: Exception) {
                                withContext(Dispatchers.Main.immediate) { state.updateOnline(false) }
                            }
                            delay(1_000)
                        }
                    }
                    launch(Dispatchers.IO) {
                        state.listen(currentPair, client)
                    }
                }
            } finally {
                state.updateOnline(false)
            }
        }
    }
}
