package com.krypt.app.deeplink

/**
 * Plaintext inside the AES-GCM sealed-box in a `krypt://approve?data=...`
 * URL. Encoded as a 5-field CBOR map with integer keys 1..5 per
 * contracts/approve.md.
 *
 * Re-carrying `req` and `app` inside the ciphertext binds the approval to
 * its originating request — even a hypothetical ciphertext forgery (which
 * AES-GCM already prevents) cannot swap approvals between requests because
 * the consumer validates these fields against the matched OutstandingRequest.
 */
data class ApprovalPayload(
    /** Protocol version ("1"). */
    val v: String,
    /** UUIDv4 of the matched request. */
    val req: String,
    /** Android package name of the target app. */
    val app: String,
    /** Grant duration in minutes; must be positive. */
    val durMin: Int,
    /** Issued-at (Guardian wall clock) in seconds since Unix epoch. */
    val iat: Long,
) {
    init {
        require(v.isNotEmpty()) { "v must not be empty" }
        require(req.isNotEmpty()) { "req must not be empty" }
        require(app.isNotEmpty()) { "app must not be empty" }
        require(durMin > 0) { "durMin ($durMin) must be positive" }
        require(iat > 0) { "iat ($iat) must be positive" }
    }
}
