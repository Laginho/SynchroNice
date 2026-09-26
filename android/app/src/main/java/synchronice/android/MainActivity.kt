package synchronice.android

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import synchronice.protocolclient.FilePendingQueueStore
import synchronice.protocolclient.Item
import synchronice.protocolclient.KnownAddressStore
import synchronice.protocolclient.PairingInfo
import synchronice.protocolclient.PendingItem
import synchronice.protocolclient.ProtocolClient
import synchronice.protocolclient.ProtocolRejectedException
import synchronice.protocolclient.parsePairingQr
import java.io.File
import java.io.IOException

class SynchroNiceApplication : Application() {
    internal lateinit var state: AppState
        private set

    override fun onCreate() {
        super.onCreate()
        state = AppState(this)
    }
}

internal enum class Delivery { PENDING, DELIVERED, FAILED }

internal data class ChatMessage(
    val localId: Long,
    val text: String,
    val outgoing: Boolean,
    val delivery: Delivery,
)

internal class AppState(context: Context) {
    private val appContext = context.applicationContext
    private val pairingPreferences = appContext.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    private val networkPreferences = appContext.getSharedPreferences("network", Context.MODE_PRIVATE)
    private val queueFile = File(appContext.filesDir, "pending-items.txt")
    private var queueStore = FilePendingQueueStore(queueFile)
    private val outboundMutex = Mutex()
    private val receivedIds = mutableSetOf<Long>()
    private var nextMessageId = 1L
    private var clientFor: PairingInfo? = null
    private var protocolClient: ProtocolClient? = null

    var pairing by mutableStateOf(readPairing())
        private set
    var online by mutableStateOf(false)
        private set
    var pairingError by mutableStateOf<String?>(null)
        private set
    var connectionError by mutableStateOf<String?>(null)
        private set
    internal val messages = mutableStateListOf<ChatMessage>()

    internal val resolver = NsdInstanceResolver(appContext)
    internal val knownAddress = object : KnownAddressStore {
        override fun get(): String? = networkPreferences.getString("last-address", null)

        override fun save(ip: String) {
            networkPreferences.edit().putString("last-address", ip).apply()
        }
    }

    init {
        queueStore.load().filterIsInstance<PendingItem.Text>().forEach { pending ->
            messages.add(ChatMessage(nextMessageId++, pending.text, true, Delivery.PENDING))
        }
    }

    fun acceptQr(json: String): Boolean {
        val newPair = try {
            parsePairingQr(json)
        } catch (_: IllegalArgumentException) {
            pairingError = "QR inválido. Aponte para o código exibido pelo SynchroNice no PC."
            return false
        }

        pairingPreferences.edit()
            .putString("instance-name", newPair.instanceName)
            .putInt("port", newPair.port)
            .putString("token", newPair.token)
            .apply()
        pairing = newPair
        pairingError = null
        connectionError = null
        online = false
        messages.clear()
        receivedIds.clear()
        protocolClient = null
        clientFor = null
        return true
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

    suspend fun sendText(id: Long, text: String) {
        val currentClient = client() ?: return
        try {
            val pending = withContext(Dispatchers.IO) {
                outboundMutex.withLock {
                    currentClient.sendText(text)
                    currentClient.hasPending()
                }
            }
            withContext(Dispatchers.Main.immediate) {
                setDelivery(id, if (pending) Delivery.PENDING else Delivery.DELIVERED)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            withContext(Dispatchers.Main.immediate) {
                setDelivery(id, Delivery.FAILED)
                connectionError = if (error is ProtocolRejectedException) {
                    "O PC recusou a mensagem (HTTP ${error.code})."
                } else {
                    "Não foi possível guardar a mensagem: ${error.message ?: "erro de rede"}"
                }
            }
        }
    }

    suspend fun retryPending() {
        val currentClient = client() ?: return
        val stillPending = outboundMutex.withLock {
            currentClient.retryPending()
            currentClient.hasPending()
        }
        if (!stillPending) {
            withContext(Dispatchers.Main.immediate) {
                messages.indices.forEach { index ->
                    val message = messages[index]
                    if (message.outgoing && message.delivery == Delivery.PENDING) {
                        messages[index] = message.copy(delivery = Delivery.DELIVERED)
                    }
                }
            }
        }
    }

    fun receive(item: Item.Text) {
        if (receivedIds.add(item.id)) {
            messages.add(ChatMessage(nextMessageId++, item.text, false, Delivery.DELIVERED))
            connectionError = null
        }
    }

    fun updateOnline(value: Boolean) {
        online = value
    }

    fun showConnectionError(message: String?) {
        connectionError = message
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = (application as SynchroNiceApplication).state
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    if (state.pairing == null) PairingScreen(state) else ChatScreen(state)
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

@Composable
private fun ChatScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    ConnectionLifecycle(state)

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().imePadding().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("SynchroNice", style = MaterialTheme.typography.titleLarge)
            Text(if (state.online) "Na rede" else "Fora da rede", color = if (state.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
        state.connectionError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
        if (state.messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("O chat está vazio. Digite uma mensagem para enviar ao PC.", textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages, key = { it.localId }) { message ->
                    MessageBubble(message)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Mensagem") },
                maxLines = 4,
            )
            Button(
                enabled = draft.isNotBlank(),
                onClick = {
                    val text = draft
                    val id = state.addOutgoing(text)
                    draft = ""
                    scope.launch { state.sendText(id, text) }
                },
            ) { Text("Enviar") }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.sizeIn(maxWidth = 320.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (message.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(message.text)
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
    LaunchedEffect(lifecycle, pairing) {
        val currentPair = pairing ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            clearPresenceDuringLifecycleCycle(state::updateOnline) cycle@{
                val client = state.client() ?: return@cycle
                coroutineScope {
                    launch(Dispatchers.IO) {
                        while (isActive) {
                            try {
                                val address = state.resolver.resolve(currentPair.instanceName)
                                if (address != null) state.knownAddress.save(address)
                                state.retryPending()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                // DNS lookup and send retries do not define the /listen presence.
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
                                withContext(Dispatchers.Main.immediate) {
                                    state.updateOnline(false)
                                    state.showConnectionError("O PC recusou o Par (HTTP ${error.code}).")
                                }
                                break
                            } catch (_: Exception) {
                                withContext(Dispatchers.Main.immediate) { state.updateOnline(false) }
                            }
                            delay(1_000)
                        }
                    }
                    launch(Dispatchers.IO) {
                        while (isActive) {
                            try {
                                val items = kotlinx.coroutines.runInterruptible { client.listen() }
                                withContext(Dispatchers.Main.immediate) {
                                    state.updateOnline(false)
                                    items.filterIsInstance<Item.Text>().forEach(state::receive)
                                }
                                items.filterIsInstance<Item.Text>().forEach { client.acknowledgeText(it) }
                                if (items.none { it is Item.Text }) delay(1_500)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: ProtocolRejectedException) {
                                withContext(Dispatchers.Main.immediate) {
                                    state.updateOnline(false)
                                    state.showConnectionError("O PC recusou o Par (HTTP ${error.code}).")
                                }
                                break
                            } catch (error: IOException) {
                                withContext(Dispatchers.Main.immediate) { state.updateOnline(false) }
                                delay(1_000)
                            } catch (_: Exception) {
                                withContext(Dispatchers.Main.immediate) { state.updateOnline(false) }
                                delay(1_500)
                            }
                        }
                    }
                }
            }
        }
    }
}

internal suspend fun clearPresenceDuringLifecycleCycle(
    updateOnline: (Boolean) -> Unit,
    block: suspend () -> Unit,
) {
    updateOnline(false)
    try {
        block()
    } finally {
        updateOnline(false)
    }
}
