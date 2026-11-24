// Windows only: puts the app icon into privacy-music.exe (Explorer, taskbar, Start menu).
fn main() {
    // Without these, Cargo watches the whole folder, including makepkg's locked packaging/pkg (build fails there).
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=assets/windows.rc");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        embed_resource::compile("assets/windows.rc", embed_resource::NONE).manifest_optional().unwrap();
    }
}
