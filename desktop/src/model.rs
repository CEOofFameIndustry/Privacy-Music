//! App-level models (independent of the YouTube client), serialisable for the local library.
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub struct Ref {
    pub name: String,
    pub id: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct Song {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub artists: Vec<Ref>,
    #[serde(default)]
    pub album: Option<Ref>,
    #[serde(default)]
    pub duration: Option<u32>,
    #[serde(default)]
    pub thumbnail: Option<String>,
}

impl Song {
    pub fn artist_text(&self) -> String {
        self.artists.iter().map(|a| a.name.as_str()).collect::<Vec<_>>().join(", ")
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub enum Item {
    Song(Song),
    Album { id: String, title: String, artists: Vec<Ref>, year: Option<u16>, thumbnail: Option<String> },
    Artist { id: String, name: String, subtitle: String, thumbnail: Option<String> },
    Playlist { id: String, title: String, subtitle: String, thumbnail: Option<String> },
    Local { id: i64, title: String, count: i64, thumbnail: Option<String> },
}

impl Item {
    pub fn title(&self) -> &str {
        match self {
            Item::Song(s) => &s.title,
            Item::Album { title, .. } | Item::Playlist { title, .. } | Item::Local { title, .. } => title,
            Item::Artist { name, .. } => name,
        }
    }
    pub fn thumbnail(&self) -> Option<&str> {
        match self {
            Item::Song(s) => s.thumbnail.as_deref(),
            Item::Album { thumbnail, .. } | Item::Artist { thumbnail, .. } | Item::Playlist { thumbnail, .. } | Item::Local { thumbnail, .. } => thumbnail.as_deref(),
        }
    }
    pub fn subtitle(&self) -> String {
        match self {
            Item::Song(s) => {
                let mut parts = vec![s.artist_text()];
                if let Some(a) = &s.album { parts.push(a.name.clone()) }
                if let Some(d) = s.duration { parts.push(fmt_dur(d)) }
                parts.retain(|p| !p.is_empty());
                parts.join(" • ")
            }
            Item::Album { artists, year, .. } => {
                let mut p = vec![artists.iter().map(|a| a.name.clone()).collect::<Vec<_>>().join(", ")];
                if let Some(y) = year { p.push(y.to_string()) }
                p.retain(|x| !x.is_empty());
                p.join(" • ")
            }
            Item::Artist { subtitle, .. } | Item::Playlist { subtitle, .. } => subtitle.clone(),
            Item::Local { count, .. } => crate::i18n::t("Плейлист • {0}", &[&count.to_string()]),
        }
    }
    pub fn key(&self) -> String {
        match self {
            Item::Song(s) => format!("s:{}", s.id),
            Item::Album { id, .. } => format!("a:{id}"),
            Item::Artist { id, .. } => format!("r:{id}"),
            Item::Playlist { id, .. } => format!("p:{id}"),
            Item::Local { id, .. } => format!("l:{id}"),
        }
    }
    pub fn is_round(&self) -> bool { matches!(self, Item::Artist { .. }) }
}

#[derive(Clone, Debug, PartialEq)]
pub struct Shelf {
    pub id: Option<String>,
    pub title: String,
    pub items: Vec<Item>,
    /// Shown as a vertical list of rows instead of a carousel of cards.
    pub list: bool,
}

pub fn fmt_dur(secs: u32) -> String {
    if secs >= 3600 { format!("{}:{:02}:{:02}", secs / 3600, secs / 60 % 60, secs % 60) } else { format!("{}:{:02}", secs / 60, secs % 60) }
}

pub fn fmt_ms(ms: u64) -> String { fmt_dur((ms / 1000) as u32) }
