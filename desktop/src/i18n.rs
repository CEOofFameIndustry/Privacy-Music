//! Same catalogue as the Android app (app/src/main/assets/i18n): Ukrainian source text → id → translation.
//! `t("Ukrainian text", &[args])` with {0}, {1} placeholders, exactly like `T()` on the phone.
use once_cell::sync::Lazy;
use parking_lot::RwLock;
use std::collections::HashMap;

macro_rules! cat { ($l:literal) => { include_str!(concat!("../../app/src/main/assets/i18n/", $l, ".json")) } }

static SOURCE: Lazy<HashMap<String, String>> = Lazy::new(|| {
    let m: HashMap<String, String> = serde_json::from_str(cat!("source")).unwrap_or_default();
    m.into_iter().map(|(id, uk)| (uk, id)).collect()
});

fn raw(lang: &str) -> Option<&'static str> {
    Some(match lang {
        "en" => cat!("en"), "es" => cat!("es"), "de" => cat!("de"), "fr" => cat!("fr"), "pt" => cat!("pt"),
        "it" => cat!("it"), "pl" => cat!("pl"), "tr" => cat!("tr"), "zh" => cat!("zh"), "ja" => cat!("ja"),
        _ => return None,
    })
}

/// Interface languages: code + native name ("system" follows the desktop locale).
pub const LANGUAGES: &[(&str, &str)] = &[
    ("uk", "Українська"), ("en", "English"), ("es", "Español"), ("fr", "Français"), ("de", "Deutsch"), ("pt", "Português"),
    ("it", "Italiano"), ("pl", "Polski"), ("tr", "Türkçe"), ("zh", "中文"), ("ja", "日本語"),
];

static CURRENT: Lazy<RwLock<(String, HashMap<String, String>)>> = Lazy::new(|| RwLock::new((String::new(), HashMap::new())));

pub fn system_lang() -> String {
    std::env::var("LC_ALL").ok().filter(|v| !v.is_empty())
        .or_else(|| std::env::var("LC_MESSAGES").ok().filter(|v| !v.is_empty()))
        .or_else(|| std::env::var("LANG").ok())
        .map(|v| v.chars().take(2).collect::<String>().to_lowercase())
        .unwrap_or_else(|| "en".into())
}

/// Switch the interface language ("system" = from the environment).
pub fn set_language(code: &str) {
    let code = if code == "system" { system_lang() } else { code.to_string() };
    let map = raw(&code).and_then(|r| serde_json::from_str::<HashMap<String, String>>(r).ok()).unwrap_or_default();
    *CURRENT.write() = (code, map);
}

pub fn current() -> String { CURRENT.read().0.clone() }

pub fn t(source: &str, args: &[&str]) -> String {
    let cur = CURRENT.read();
    let text = if cur.0 == "uk" { None } else { SOURCE.get(source).and_then(|id| cur.1.get(id)).cloned() };
    let mut out = text.unwrap_or_else(|| source.to_string());
    for (i, a) in args.iter().enumerate() { out = out.replace(&format!("{{{i}}}"), a); }
    out
}

/// `tr!("text")` / `tr!("text {0}", arg)`
#[macro_export]
macro_rules! tr {
    ($s:expr) => { $crate::i18n::t($s, &[]) };
    ($s:expr, $($a:expr),+) => { $crate::i18n::t($s, &[$(&$a.to_string()),+]) };
}
