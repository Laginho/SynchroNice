package synchronice.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class DebugPairActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val qr = intent.getStringExtra("qr")
        if (qr != null && (application as SynchroNiceApplication).state.acceptQr(qr)) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        finish()
    }
}
