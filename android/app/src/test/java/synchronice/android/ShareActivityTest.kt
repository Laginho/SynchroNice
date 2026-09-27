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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** URI de outro app apontando para dentro do próprio SynchroNice: nunca deve virar Pendente. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ShareActivityTest {
    /** Um provider de outro app, servindo `content` sob `name`. */
    private fun registerForeignProvider(authority: String, name: String, content: String) {
        val application = RuntimeEnvironment.getApplication()
        ShadowContentResolver.registerProviderInternal(authority, object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(name)) }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
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
    fun `fileUriForOwnInternalFilesDoesNotQueue`() {
        val application = RuntimeEnvironment.getApplication()
        val state = share(ownInternalFileUri(application))
        assertTrue(state.messages.isEmpty())
    }

    // A resolução real do FileProvider (mesma authority do app) não é fiável sob Robolectric
    // em Windows: SimplePathStrategy compara o caminho canônico (barras invertidas) com a raiz
    // configurada usando barra normal, e nunca casa. Testa a filtragem diretamente, sem depender
    // do ContentResolver: URI é excluída antes de qualquer leitura, então nenhum provider real
    // precisa responder.
    @Test
    fun `contentUriFromOwnFileProviderIsFilteredOutBeforeReading`() {
        val application = RuntimeEnvironment.getApplication()
        val ownAuthorityUri = Uri.parse("content://${application.packageName}.camera/camera/foto.jpg")
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, ownAuthorityUri)
        val activity = Robolectric.buildActivity(ShareActivity::class.java, intent).get()
        val method = ShareActivity::class.java.getDeclaredMethod("sharedUris", Intent::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val accepted = method.invoke(activity, intent) as List<Uri>
        assertFalse(accepted.contains(ownAuthorityUri))
    }

    @Test
    fun `contentUriFromAnotherProviderQueuesNormally`() {
        registerForeignProvider("test.provider", "arquivo.txt", "conteudo")
        val state = share(Uri.parse("content://test.provider/arquivo.txt"))
        assertEquals(1, state.messages.size)
        assertEquals("arquivo.txt", state.messages.single().text)
    }

    @Test
    fun `sendMultipleOnlyQueuesTheAcceptedUri`() {
        registerForeignProvider("test.provider", "arquivo.txt", "conteudo")
        val application = RuntimeEnvironment.getApplication()
        val state = share(
            Uri.parse("content://test.provider/arquivo.txt"),
            ownInternalFileUri(application),
        )
        assertEquals(1, state.messages.size)
        assertEquals("arquivo.txt", state.messages.single().text)
    }
}
