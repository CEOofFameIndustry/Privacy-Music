package com.example.privacymusic

/**
 * Author's links shown in Налаштування → Підтримка. Fill these in before publishing; empty values are hidden.
 * (Kept in one place so nothing else in the app has to know about them.)
 */
object Links {
    /** Support is crypto only (same list as the desktop app, desktop/src/links.rs). Empty address = "coming soon". */
    data class Donation(val coin: String, val network: String, val address: String, val recommended: Boolean = false)
    val donations: List<Donation> = listOf(
        Donation("Monero (XMR)", "Monero", "89DRpjfQeyjSxpsKYsd2dwJwjCjqLRjsrEf3kWcjKYyM5BRykkY2qiqEoJmPZrCpLR3J7jqLFUrgdEbYzF7ELLk28oYcQvS", recommended = true),
        Donation("Bitcoin (BTC)", "Bitcoin", "bc1qknsaqeupv2z9v24m7mvh0zxutewq32lqr7l2cv"),
        Donation("Tether (USDT)", "Arbitrum One", "0x2E6568ec773f220e7ED672Bd54AaF8Bd20f24c81"),
    )

    /** Public bug tracker, e.g. "https://github.com/<user>/<repo>/issues/new". Empty = only the share sheet is offered. */
    const val ISSUES = "https://github.com/CEOofFameIndustry/Privacy-Music/issues/new"

    /** Address for bug reports by e-mail. Empty = the user picks any app from the share sheet. */
    const val BUG_EMAIL = "2secondary2surfaced@protonmail.com"
}
