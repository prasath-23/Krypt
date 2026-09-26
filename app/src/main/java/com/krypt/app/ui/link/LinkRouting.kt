package com.krypt.app.ui.link

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import com.krypt.app.deeplink.KryptLinks
import com.krypt.app.subject.ApprovalTrampolineActivity
import com.krypt.app.ui.guardian.GuardianActivity

/** Opens [link] in the Krypt screen that handles it: approvals silently, requests in the Guardian screen. */
fun kryptLinkIntent(context: Context, link: String): Intent {
    val target = if (KryptLinks.isApproval(link)) {
        ApprovalTrampolineActivity::class.java
    } else {
        GuardianActivity::class.java
    }
    return Intent(Intent.ACTION_VIEW, Uri.parse(link), context, target)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * Opens the Krypt link found in [text]. With [approvalsOnly], request links
 * are ignored. Returns false when no suitable link was found.
 */
fun Context.openKryptLinkIn(text: CharSequence?, approvalsOnly: Boolean = false): Boolean {
    val link = KryptLinks.find(text) ?: return false
    if (approvalsOnly && !KryptLinks.isApproval(link)) return false
    startActivity(kryptLinkIntent(this, link))
    return true
}

/**
 * An action that opens the Krypt link on the clipboard (see
 * [openKryptLinkIn]); [onNoLink] runs when there is none.
 */
@Composable
fun pasteKryptLinkAction(approvalsOnly: Boolean = false, onNoLink: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    return {
        if (!context.openKryptLinkIn(clipboard.getText()?.text, approvalsOnly)) onNoLink()
    }
}

/**
 * A share-sheet chooser for [text] that leaves out Krypt's own "Open in
 * Krypt" target, which would only route the link back into this device.
 */
fun shareTextChooser(context: Context, text: String, title: CharSequence?): Intent =
    Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
        title,
    ).putExtra(
        Intent.EXTRA_EXCLUDE_COMPONENTS,
        arrayOf(ComponentName(context, LinkReceiverActivity::class.java)),
    )
