package com.krypt.app.deeplink

import com.krypt.app.common.Outcome
import com.krypt.app.crypto.KdfProvider
import com.krypt.app.data.OutstandingRequest
import com.krypt.app.data.OutstandingRequestRepository
import com.krypt.app.data.settings.SettingsRepository
import com.krypt.app.security.MasterKeyStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Subject-side issuer of `krypt://request` URLs; the counterpart of
 * [ApprovalConsumer].
 *
 * Every issued URL is backed by an [OutstandingRequest] row. The consumer
 * only accepts an approval whose `req` matches an open row, so a URL shared
 * without its row can never be approved.
 */
@Singleton
class UnlockRequestIssuer @Inject constructor(
    private val masterKeyStore: MasterKeyStore,
    private val settings: SettingsRepository,
    private val builder: UnlockRequestBuilder,
    private val outstandingRepo: OutstandingRequestRepository,
) {

    sealed interface IssueError {
        /** No Guardian PIN has been set up on this device yet. */
        data object NotConfigured : IssueError
    }

    /** Build a request URL for [targetPackage] and persist its [OutstandingRequest]. */
    suspend fun issue(targetPackage: String): Outcome<String, IssueError> {
        val salt = masterKeyStore.loadSalt()
        val pinProof = masterKeyStore.loadPinProof()
        if (salt == null || pinProof == null) {
            return Outcome.err(IssueError.NotConfigured)
        }

        val (url, request) = builder.build(
            setupSalt = salt,
            pinProof = pinProof,
            targetPackage = targetPackage,
            kdfIterations = setupKdfIterations(),
        )
        outstandingRepo.pruneStale(request.issuedAt * 1000)
        outstandingRepo.insert(
            OutstandingRequest(
                requestId = request.requestId,
                targetPackage = request.targetPackage,
                salt = request.salt,
                issuedAtMs = request.issuedAt * 1000,
                expiresAtMs = request.expiresAtSeconds * 1000,
                consumed = false,
            )
        )
        return Outcome.ok(url)
    }

    /** The iteration count PIN setup derived MasterKey with (see LocalPinValidator). */
    private suspend fun setupKdfIterations(): Int = try {
        settings.settings.first().kdfIterations
    } catch (_: Throwable) {
        KdfProvider.MIN_ITERATIONS
    }
}
