package com.krypt.app.deeplink

/**
 * Centralised constants for Krypt's deep-link wire format.
 *
 * See polaris-specs/001-krypt-app-locker/control-map.md for a high-level
 * summary and the per-URL grammar in contracts/.
 *
 * Every URL in the protocol has shape:
 *   krypt://<authority>?v=1&<params>
 *
 * Authorities are disjoint; they select the handler in GuardianActivity
 * (WP14).
 */
object DeepLinkScheme {

    const val SCHEME = "krypt"

    const val AUTHORITY_PAIR = "pair"
    const val AUTHORITY_PAIRED = "paired"
    const val AUTHORITY_REQUEST = "request"
    const val AUTHORITY_APPROVE = "approve"

    /** Protocol version. A parser MUST fail fast on mismatches. */
    const val PROTOCOL_VERSION = "1"

    /** Canonical query-parameter names (shared across WP03 + WP04). */
    object Params {
        const val VERSION = "v"
        const val SUBJECT_ID = "sub"
        const val SUBJECT_NAME = "subName"
        const val GUARDIAN_NAME = "guardianName"
        const val EPH_PUB = "ephPub"
        const val PUB_SALT = "pubSalt"
        const val KDF_ITER = "kdfIter"
        const val MAC = "mac"
        const val REQUEST_ID = "req"
        const val APP_PACKAGE = "app"
        const val SALT = "salt"
        const val DATA = "data"
        const val ISSUED_AT = "iat"
        const val TTL = "ttl"

        /** Amendment 1: HMAC-SHA-256 tag embedded in `krypt://request?...`. */
        const val PIN_PROOF = "pinProof"
    }
}
