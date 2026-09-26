package synchronice.android

import android.os.Looper
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PresenceLifecycleTest {
    @Test
    fun pausingAndResumingChatClearsTheVisiblePresence() {
        val state = (RuntimeEnvironment.getApplication() as SynchroNiceApplication).state
        state.acceptQr("""{"instance_name":"synchronice-1234abcd","port":7777,"token":"0123456789abcdef0123456789abcdef"}""")
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()

        state.updateOnline(true)
        activity.pause()
        assertFalse(state.online)

        state.updateOnline(true)
        activity.resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(state.online)
        activity.close()
    }
}
