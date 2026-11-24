//! "A new version is out": asks GitHub's public API for the latest release of the project (no account, no ids;
//! GitHub sees only the IP address, like any website). Can be turned off in Settings → General.
use once_cell::sync::Lazy;
use parking_lot::Mutex;

pub const REPO: &str = "CEOofFameIndustry/Privacy-Music";
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

#[derive(Clone, Debug, PartialEq, Default)]
pub struct Release { pub version: String, pub url: String, pub notes: String }

/// The newer release found by the last check (None = up to date / not checked).
pub static AVAILABLE: Lazy<Mutex<Option<Release>>> = Lazy::new(Default::default);

fn parse(v: &str) -> Vec<u64> { v.trim_start_matches(['v', 'V']).split(['.', '-']).map_while(|p| p.parse().ok()).collect() }
pub fn newer(remote: &str, local: &str) -> bool { parse(remote) > parse(local) }

/// Checks now. Ok(Some) = a newer version exists.
pub async fn check() -> anyhow::Result<Option<Release>> {
    let url = format!("https://api.github.com/repos/{REPO}/releases/latest");
    let v: serde_json::Value = crate::firewall::HTTP.get(&url).header("Accept", "application/vnd.github+json")
        .send().await?.error_for_status()?.json().await?;
    let tag = v["tag_name"].as_str().unwrap_or_default().to_string();
    let rel = Release { version: tag.trim_start_matches('v').to_string(), url: v["html_url"].as_str().unwrap_or_default().to_string(),
        notes: v["body"].as_str().unwrap_or_default().chars().take(600).collect() };
    let found = newer(&tag, VERSION).then_some(rel);
    *AVAILABLE.lock() = found.clone();
    Ok(found)
}

/// At start and then twice a day, while the setting is on.
pub async fn run() {
    tokio::time::sleep(std::time::Duration::from_secs(20)).await;
    loop {
        let s = crate::settings::get();
        if s.update_check && !s.offline { let _ = check().await; }
        tokio::time::sleep(std::time::Duration::from_secs(12 * 3600)).await;
    }
}

/// The one command that installs / updates the app on this system.
pub fn install_command() -> &'static str {
    if cfg!(windows) { "irm https://raw.githubusercontent.com/CEOofFameIndustry/Privacy-Music/main/install.ps1 | iex" }
    else { "curl -fsSL https://raw.githubusercontent.com/CEOofFameIndustry/Privacy-Music/main/install.sh | bash" }
}

#[cfg(test)]
mod tests {
    #[test]
    fn versions() {
        assert!(super::newer("v3.0.1", "3.0.0"));
        assert!(super::newer("v3.1.0", "3.0.9"));
        assert!(!super::newer("v3.0.0", "3.0.0"));
        assert!(!super::newer("v2.9.9", "3.0.0"));
    }
}
