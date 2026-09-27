package com.krypt.app.deeplink

/**
 * Plaintext inside the AES-GCM sealed-box in a `krypt://approve?data=...`
 * URL. Encoded as a CBOR map with integer keys per contracts/approve.md:
 * keys 1..5 for a one-time unlock, plus key 6 ([days]) for an every-day one.
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
    /** Grant duration in minutes: 1 to [AccessChoice.MAX_MINUTES]. */
    val durMin: Int,
    /** Issued-at (Guardian wall clock) in seconds since Unix epoch. */
    val iat: Long,
    /**
     * Every-day approvals only: for how many days the app may be used for
     * [durMin] minutes a day. Null for a one-time unlock of [durMin] minutes.
     */
    val days: Int? = null,
) {
    init {
        require(v.isNotEmpty()) { "v must not be empty" }
        require(req.isNotEmpty()) { "req must not be empty" }
        require(app.isNotEmpty()) { "app must not be empty" }
        require(durMin in 1..AccessChoice.MAX_MINUTES) {
            "durMin ($durMin) must be in 1..${AccessChoice.MAX_MINUTES}"
        }
        require(iat > 0) { "iat ($iat) must be positive" }
        require(days == null || days in 1..AccessChoice.MAX_DAYS) {
            "days ($days) must be in 1..${AccessChoice.MAX_DAYS}"
        }
    }

    /** What the approval allows. */
    val access: AccessChoice
        get() = if (days == null) AccessChoice.OneTime(durMin) else AccessChoice.EveryDay(durMin, days)
}
