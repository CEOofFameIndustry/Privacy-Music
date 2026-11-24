//! Where to send money to the author. One list, one place: add a coin here and it appears on the Support page with a
//! copy button and a QR code. (Addresses are only ever shown on screen — the app never contacts anything with them.)

pub struct Donation {
    /// "Bitcoin", "USDT", "TON"…
    pub coin: &'static str,
    /// "Bitcoin network", "TRC-20", …
    pub network: &'static str,
    /// Empty = not published yet (the coin is listed as "coming soon").
    pub address: &'static str,
    /// Shown first with a "recommended" badge.
    pub recommended: bool,
}

/// Crypto only, same list as the phone (app/.../Links.kt). Monero is recommended: private by design.
pub const DONATIONS: &[Donation] = &[
    Donation { coin: "Monero (XMR)", network: "Monero", address: "89DRpjfQeyjSxpsKYsd2dwJwjCjqLRjsrEf3kWcjKYyM5BRykkY2qiqEoJmPZrCpLR3J7jqLFUrgdEbYzF7ELLk28oYcQvS", recommended: true },
    Donation { coin: "Bitcoin (BTC)", network: "Bitcoin", address: "bc1qknsaqeupv2z9v24m7mvh0zxutewq32lqr7l2cv", recommended: false },
    Donation { coin: "Tether (USDT)", network: "Arbitrum One", address: "0x2E6568ec773f220e7ED672Bd54AaF8Bd20f24c81", recommended: false },
];
