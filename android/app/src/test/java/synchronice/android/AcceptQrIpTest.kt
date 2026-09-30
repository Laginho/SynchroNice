package synchronice.android

import android.content.Context
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AcceptQrIpTest {
    private lateinit var context: Context
    private val base = """"instance_name":"synchronice-1234abcd","port":7777,"token":"0123456789abcdef0123456789abcdef""""

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("pairing", 0).edit().clear().commit()
        context.getSharedPreferences("network", 0).edit().clear().commit()
    }

    @Test
    fun acceptQrSavesTheQrIpAsKnownAddress() {
        val state = AppState(context)
        assertNull(state.knownAddress.get())
        assertTrue(state.acceptQr("""{$base,"ip":"192.168.18.12"}"""))
        assertEquals("192.168.18.12", state.knownAddress.get())
    }

    @Test
    fun acceptQrWithoutIpLeavesKnownAddressAlone() {
        val state = AppState(context)
        state.knownAddress.save("10.0.0.5")
        assertTrue(state.acceptQr("""{$base}"""))
        assertEquals("10.0.0.5", state.knownAddress.get())
    }

    @Test
    fun rescanningTheSamePcWithANewIpIsNotANewPair() {
        val state = AppState(context)
        assertTrue(state.acceptQr("""{$base,"ip":"192.168.18.12"}"""))
        val pair = state.pairing
        assertTrue(state.acceptQr("""{$base,"ip":"192.168.18.99"}"""))
        assertEquals(pair, state.pairing)
        assertEquals("192.168.18.99", state.knownAddress.get())
    }
}
