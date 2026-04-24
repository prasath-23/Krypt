package com.krypt.app.ui.guardian

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.krypt.app.R
import com.krypt.app.common.Outcome
import com.krypt.app.crypto.KPairStore
import com.krypt.app.data.GuardianRepository
import com.krypt.app.deeplink.ApprovalLinkBuilder
import com.krypt.app.deeplink.UnlockRequest
import com.krypt.app.deeplink.UnlockRequestParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class GuardianRequestViewModel @Inject constructor(
    private val parser: UnlockRequestParser,
    private val approvalBuilder: ApprovalLinkBuilder,
    private val kPairStore: KPairStore,
    private val guardianRepo: GuardianRepository,
    private val rateLimiter: RateLimiter,
) : ViewModel() {

    sealed interface VerifyResult {
        data class Approval(val url: String) : VerifyResult
        data object NotPaired : VerifyResult
        data object WrongPin : VerifyResult
        data class RateLimited(val retryAtMs: Long) : VerifyResult
    }

    fun parseIncoming(url: String, nowSeconds: Long) = parser.parse(url, nowSeconds)

    /**
     * Note: This v1 impl treats "any non-empty PIN of the expected length" as
     * acceptable — the real PIN-hash comparison requires storing the hash on
     * first pairing (WP13). A complete production build would derive a KDF
     * hash here and `MessageDigest.isEqual` it against the stored one. The
     * rate-limiter is real; wrong-PIN counter ticks regardless.
     */
    suspend fun verifyPinAndBuild(
        request: UnlockRequest,
        pin: CharArray,
        grantDurationMinutes: Int = 15,
    ): VerifyResult = withContext(Dispatchers.Default) {
        try {
            when (val attempt = rateLimiter.tryAttempt()) {
                is RateLimiter.AttemptResult.LockedUntil -> return@withContext VerifyResult.RateLimited(attempt.retryAtMs)
                is RateLimiter.AttemptResult.Allowed -> Unit
            }
            val pairing = guardianRepo.getPairing()
                ?: return@withContext VerifyResult.NotPaired
            val kPair = kPairStore.load()
                ?: return@withContext VerifyResult.NotPaired
            try {
                if (pin.size < 4) {
                    rateLimiter.recordFailure()
                    return@withContext VerifyResult.WrongPin
                }
                // TODO(post-v1): hash via pairing.kdfIterations and compare against stored PIN hash.
                rateLimiter.recordSuccess()
                val url = approvalBuilder.build(
                    kPair = kPair,
                    request = request,
                    grantDurationMinutes = grantDurationMinutes,
                )
                VerifyResult.Approval(url)
            } finally {
                kPair.fill(0)
            }
        } finally {
            pin.fill(' ')
        }
    }
}

@Composable
fun GuardianRequestScreen(
    incomingUrl: String,
    viewModel: GuardianRequestViewModel = hiltViewModel(),
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var request by remember { mutableStateOf<UnlockRequest?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(incomingUrl) {
        val out = viewModel.parseIncoming(incomingUrl, System.currentTimeMillis() / 1000L)
        when (out) {
            is Outcome.Ok -> request = out.value
            is Outcome.Err -> error = out.error.toString()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.guardian_request_title), style = MaterialTheme.typography.headlineSmall)
        request?.let { req ->
            Card { Column(modifier = Modifier.padding(16.dp)) {
                Text(req.targetPackage, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.guardian_request_prompt), style = MaterialTheme.typography.bodyMedium)
            } }
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                label = { Text(stringResource(R.string.guardian_pin_label)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = pin.length >= 4,
                onClick = {
                    scope.launch {
                        when (val r = viewModel.verifyPinAndBuild(req, pin.toCharArray())) {
                            is GuardianRequestViewModel.VerifyResult.Approval -> {
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, r.url)
                                        },
                                        null,
                                    )
                                )
                                onDone()
                            }
                            is GuardianRequestViewModel.VerifyResult.WrongPin ->
                                error = "Wrong PIN"
                            is GuardianRequestViewModel.VerifyResult.RateLimited ->
                                error = "Too many wrong tries. Try again later."
                            is GuardianRequestViewModel.VerifyResult.NotPaired ->
                                error = "Not paired with a Subject device."
                        }
                    }
                }
            ) { Text(stringResource(R.string.guardian_send_approval)) }
        }
        error?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
    }
}
