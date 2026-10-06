package com.krypt.app.deeplink

/**
 * Finds Krypt links inside arbitrary text, such as a whole message copied
 * from a chat. Many messaging apps only make `https://` links tappable, so
 * pasting or sharing the message into Krypt is the fallback for `krypt://`
 * links.
 */
object KryptLinks {

    // Query values are URL-encoded (UrlCodec) and Base64url, so a link is a
    // run of these characters; anything else (whitespace, quotes) ends it.
    private val LINK = Regex(
        "${DeepLinkScheme.SCHEME}://" +
            "(${DeepLinkScheme.AUTHORITY_REQUEST}|${DeepLinkScheme.AUTHORITY_APPROVE})" +
            """\?[A-Za-z0-9._~*%+=&-]+""",
    )

    private const val APPROVAL_PREFIX = "${DeepLinkScheme.SCHEME}://${DeepLinkScheme.AUTHORITY_APPROVE}?"

    /**
     * The first `krypt://request` or `krypt://approve` link in [text], or
     * null. A trailing full stop is dropped: every Krypt link ends in digits.
     */
    fun find(text: CharSequence?): String? =
        text?.let(LINK::find)?.value?.trimEnd('.')

    fun isApproval(link: String): Boolean = link.startsWith(APPROVAL_PREFIX)
}
