use serde::{Deserialize, Serialize};
use std::io;
use std::path::{Path, PathBuf};

const FILE_NAME: &str = "config.json";

#[derive(Serialize, Deserialize)]
struct ConfigFile {
    pasta: PathBuf,
}

/// A Pasta escolhida, ou `None` se o app nunca perguntou ("não escolhida").
pub fn load_pasta(config_dir: &Path) -> Option<PathBuf> {
    let data = std::fs::read_to_string(config_dir.join(FILE_NAME)).ok()?;
    let config: ConfigFile = serde_json::from_str(&data).ok()?;
    Some(config.pasta)
}

pub fn save_pasta(config_dir: &Path, pasta: &Path) -> io::Result<()> {
    std::fs::create_dir_all(config_dir)?;
    let config = ConfigFile {
        pasta: pasta.to_path_buf(),
    };
    let data = serde_json::to_string_pretty(&config).expect("ConfigFile serializes");
    std::fs::write(config_dir.join(FILE_NAME), data)
}
