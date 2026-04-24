package com.krypt.app.ui.pairing

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.krypt.app.R
import com.krypt.app.crypto.X25519KeyAgreement
import com.krypt.app.deeplink.PairRequestBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class SubjectPairViewModel @Inject constructor(
    private val builder: PairRequestBuilder,
    private val session: PairingSession,
) : ViewModel() {

    fun generatePairLink(subjectDisplayName: String): String {
        val keyPair = X25519KeyAgreement.generateEphemeralKeyPair()
        val subjectId = UUID.randomUUID()
        session.store(subjectId, keyPair)
        val (url, _) = builder.build(
            subjectId = subjectId,
            subjectDisplayName = subjectDisplayName,
            ephPrivate = keyPair.private,
        )
        return url
    }

    override fun onCleared() {
        // DO NOT clear session here — SubjectPairedConsumeScreen needs the
        // ephPrivate after navigation. PairingSession holds a TTL internally
        // (documented limitation).
    }
}

@Composable
fun SubjectPairScreen(viewModel: SubjectPairViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var displayName by rememberSaveable { mutableStateOf(Build.MODEL) }
    var url by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.pair_subject_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.pair_subject_body), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text(stringResource(R.string.pair_display_name_label)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { url = viewModel.generatePairLink(displayName) }) {
            Text(stringResource(R.string.pair_generate))
        }
        url?.let { generated ->
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = generated,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Button(
                onClick = {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, generated)
                            },
                            null,
                        )
                    )
                }
            ) { Text(stringResource(R.string.pair_share)) }
            Text(
                text = stringResource(R.string.pair_waiting),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
