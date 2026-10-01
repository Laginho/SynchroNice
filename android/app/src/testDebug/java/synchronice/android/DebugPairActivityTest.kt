package synchronice.android

import android.content.Intent
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugPairActivityTest {
    @Test
    fun validQrPersistsThePairAndAddressAndOpensChat() {
        val application = RuntimeEnvironment.getApplication() as SynchroNiceApplication
        val json = """{"instance_name":"synchronice-1234abcd","port":47820,"token":"0123456789abcdef0123456789abcdef","ip":"192.168.18.12"}"""
        val intent = Intent(application, DebugPairActivity::class.java).putExtra("qr", json)
        val activity = Robolectric.buildActivity(DebugPairActivity::class.java, intent).create().get()

        assertEquals("synchronice-1234abcd", application.state.pairing?.instanceName)
        assertEquals(47820, application.state.pairing?.port)
        assertEquals("0123456789abcdef0123456789abcdef", application.state.pairing?.token)
        assertEquals("192.168.18.12", application.state.knownAddress.get())
        val reopened = AppState(application)
        assertEquals(application.state.pairing, reopened.pairing)
        assertEquals("192.168.18.12", reopened.knownAddress.get())
        assertTrue(activity.isFinishing)
        assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
    }

    @Test
    fun invalidQrFinishesWithoutPairingOrOpeningChat() {
        val application = RuntimeEnvironment.getApplication() as SynchroNiceApplication
        val intent = Intent(application, DebugPairActivity::class.java).putExtra("qr", "{invalid}")
        val activity = Robolectric.buildActivity(DebugPairActivity::class.java, intent).create().get()

        assertNull(application.state.pairing)
        assertNull(AppState(application).pairing)
        assertNull(application.state.knownAddress.get())
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun missingQrFinishesWithoutPairingOrOpeningChat() {
        val application = RuntimeEnvironment.getApplication() as SynchroNiceApplication
        val activity = Robolectric.buildActivity(DebugPairActivity::class.java).create().get()

        assertNull(application.state.pairing)
        assertNull(AppState(application).pairing)
        assertNull(application.state.knownAddress.get())
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
