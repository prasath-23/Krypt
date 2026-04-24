package com.krypt.app.ui.guardian

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Placeholder for WP15's full implementation. GuardianActivity dispatches
 * krypt://approve here; WP15 replaces this with a real ApprovalConsumer call
 * + success/error UI + OverlayManager.hide + target-app relaunch.
 */
@Composable
fun GuardianApprovalConsumeScreen(incomingUrl: String, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text("Consuming approval…", style = MaterialTheme.typography.bodyMedium)
        // WP15 wires ApprovalConsumer here.
    }
}
