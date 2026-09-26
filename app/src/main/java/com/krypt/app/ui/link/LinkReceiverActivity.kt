package com.krypt.app.ui.link

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.krypt.app.R

/**
 * "Open in Krypt" target for shared text (ACTION_SEND) and selected text
 * (ACTION_PROCESS_TEXT). Opens the Krypt link found in the text and
 * finishes; it has no UI of its own. This is how a Krypt link gets opened
 * from a messaging app that does not make `krypt://` links tappable.
 */
class LinkReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        if (!openKryptLinkIn(text)) {
            Toast.makeText(this, R.string.link_not_found, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
