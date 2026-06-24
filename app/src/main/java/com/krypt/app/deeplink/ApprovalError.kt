package com.krypt.app.deeplink

/**
 * Sealed set of observable error states of [ApprovalConsumer.consume].
 *
 * Rows correspond one-to-one with the error table in
 * polaris-specs/001-krypt-app-locker/contracts/approve.md.
 */
sealed interface ApprovalError {
    data object BadScheme : ApprovalError
    data object WrongVersion : ApprovalError
    data class MissingParam(val name: String) : ApprovalError
    data object BadBase64 : ApprovalError
    data object BadUuid : ApprovalError

    /** No OutstandingRequest with this requestId — OR already consumed. */
    data object UnmatchedRequest : ApprovalError

    /** OutstandingRequest's own TTL elapsed before the approval arrived. */
    data object RequestExpired : ApprovalError

    /** AES-GCM tag verification failed; payload was tampered with. */
    data object CipherDecryptFailed : ApprovalError

    /** Plaintext req/app didn't match URL req / OutstandingRequest.targetPackage. */
    data object PayloadInconsistent : ApprovalError

    /** K_pair not found in KPairStore — Subject device hasn't paired yet. */
    data object NotPaired : ApprovalError
}
