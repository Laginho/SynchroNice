package synchronice.android

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** URI de outro app apontando para dentro do próprio SynchroNice: nunca deve virar Pendente. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ShareActivityTest {
    /** Um provider de outro app, servindo `content` sob `name`; [onOpen] roda antes de cada leitura. */
    private fun registerForeignProvider(authority: String, name: String, content: String, onOpen: () -> Unit = {}) {
        val application = RuntimeEnvironment.getApplication()
        ShadowContentResolver.registerProviderInternal(authority, object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(name)) }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                onOpen()
                val source = File(application.cacheDir, "source-$authority-$name").apply { writeText(content) }
                return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun getType(uri: Uri) = "application/octet-stream"
        }.apply { attachInfo(application, ProviderInfo().apply { this.authority = authority }) })
    }

    private fun ownInternalFileUri(application: android.app.Application): Uri {
        val file = File(application.filesDir, "segredo-do-par.txt").apply { writeText("token-secreto") }
        return Uri.fromFile(file)
    }

    private fun share(vararg uris: Uri) = runBlocking {
        val application = RuntimeEnvironment.getApplication() as SynchroNiceApplication
        application.state.acceptQr("""{"instance_name":"synchronice-1234abcd","port":7777,"token":"0123456789abcdef0123456789abcdef"}""")
        val intent = when {
            uris.size > 1 -> Intent(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris.toList()))
            uris.size == 1 -> Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else -> Intent(Intent.ACTION_SEND)
        }
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val activity = Robolectric.buildActivity(ShareActivity::class.java, intent).create().get()
            withTimeout(5_000) {
                while (!activity.isFinishing) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
        application.state
    }

    @Test
    fun fileUriForOwnInternalFilesDoesNotQueue() {
        val application = RuntimeEnvironment.getApplication()
        val state = share(ownInternalFileUri(application))
        assertTrue(state.messages.isEmpty())
    }

    @Test
    fun contentUriFromAnotherProviderQueuesNormally() {
        registerForeignProvider("test.provider", "arquivo.txt", "conteudo")
        val state = share(Uri.parse("content://test.provider/arquivo.txt"))
        assertEquals(1, state.messages.size)
        assertEquals("arquivo.txt", state.messages.single().text)
    }

    // O ContentResolver do AOSP tira o prefixo "<userId>@" da authority antes de resolver o
    // provider (ContentProvider.getAuthorityWithoutUserId), então a URI prefixada cai no
    // FileProvider da câmera tanto quanto a sem prefixo -- só que uma comparação de string crua
    // não enxerga isso.
    @Test
    fun sendMultipleOnlyQueuesTheAcceptedUri() {
        registerForeignProvider("test.provider", "arquivo.txt", "conteudo")
        val application = RuntimeEnvironment.getApplication()
        // As URIs próprias vão primeiro de propósito: se o filtro deixar passar qualquer uma, a
        // leitura dela lança sob Robolectric (o FileProvider não resolve caminhos no Windows) e
        // aborta o lote antes do Pendente de test.provider.
        val state = share(
            Uri.parse("content://${application.packageName}.camera/camera/foto.jpg"),
            Uri.parse("content://0@${application.packageName}.camera/camera/foto.jpg"),
            ownInternalFileUri(application),
            Uri.parse("content://test.provider/arquivo.txt"),
        )
        assertEquals(1, state.messages.size)
        assertEquals("arquivo.txt", state.messages.single().text)
    }

    @Test
    fun rotatingWhileSharingDoesNotQueueTheSameFilesAgain() = runBlocking {
        val secondOpened = CountDownLatch(1)
        val release = CountDownLatch(1)
        registerForeignProvider("test.provider", "um.txt", "1")
        registerForeignProvider("slow.provider", "dois.txt", "2") {
            secondOpened.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val application = RuntimeEnvironment.getApplication() as SynchroNiceApplication
        // Sem WorkManager, queueFile lança depois de pôr a bolha e o lote para no primeiro arquivo.
        WorkManagerTestInitHelper.initializeTestWorkManager(application)
        application.state.acceptQr("""{"instance_name":"synchronice-1234abcd","port":7777,"token":"0123456789abcdef0123456789abcdef"}""")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(
            Intent.EXTRA_STREAM,
            arrayListOf(Uri.parse("content://test.provider/um.txt"), Uri.parse("content://slow.provider/dois.txt")),
        )
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val controller = Robolectric.buildActivity(ShareActivity::class.java, intent).create()
            withTimeout(5_000) {
                while (secondOpened.count > 0) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            controller.recreate()
            release.countDown()
            withTimeout(5_000) {
                while (!controller.get().isFinishing) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
        } finally {
            release.countDown()
            Dispatchers.resetMain()
        }
        assertEquals(listOf("um.txt", "dois.txt"), application.state.messages.map { it.text })
    }
}
