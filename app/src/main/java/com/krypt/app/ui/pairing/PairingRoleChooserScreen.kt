package com.krypt.app.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.krypt.app.R
import com.krypt.app.data.PairingRole

@Composable
fun PairingRoleChooserScreen(onRoleChosen: (PairingRole) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.role_chooser_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.role_chooser_body), style = MaterialTheme.typography.bodyMedium)
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.role_guardian_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.role_guardian_body), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { onRoleChosen(PairingRole.GUARDIAN_OF_SUBJECT) }) {
                    Text(stringResource(R.string.role_choose))
                }
            }
        }
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.role_subject_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.role_subject_body), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { onRoleChosen(PairingRole.SUBJECT_OF_GUARDIAN) }) {
                    Text(stringResource(R.string.role_choose))
                }
            }
        }
        Text(
            text = stringResource(R.string.role_chooser_footer),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
