use serde::{Deserialize, Serialize};
use std::io;
use std::path::{Path, PathBuf};

const FILE_NAME: &str = "config.json";

/// Tudo que persiste além da Sessão: a Pasta e o token do Par.
#[derive(Serialize, Deserialize, Default)]
struct ConfigFile {
    #[serde(default)]
    pasta: Option<PathBuf>,
    #[serde(default)]
    token: Option<String>,
}

fn load(config_dir: &Path) -> ConfigFile {
    std::fs::read_to_string(config_dir.join(FILE_NAME))
        .ok()
        .and_then(|data| serde_json::from_str(&data).ok())
        .unwrap_or_default()
}

fn save(config_dir: &Path, config: &ConfigFile) -> io::Result<()> {
    std::fs::create_dir_all(config_dir)?;
    let data = serde_json::to_string_pretty(config).expect("ConfigFile serializes");
    std::fs::write(config_dir.join(FILE_NAME), data)
}

/// A Pasta escolhida, ou `None` se o app nunca perguntou ("não escolhida").
pub fn load_pasta(config_dir: &Path) -> Option<PathBuf> {
    load(config_dir).pasta
}

pub fn save_pasta(config_dir: &Path, pasta: &Path) -> io::Result<()> {
    let mut config = load(config_dir);
    config.pasta = Some(pasta.to_path_buf());
    save(config_dir, &config)
}

/// O token secreto do Par. Gerado na primeira chamada e gravado; depois,
/// sempre o mesmo. Parear de novo (SYN-005) é quem o troca.
pub fn load_or_create_token(config_dir: &Path) -> io::Result<String> {
    let mut config = load(config_dir);
    if let Some(token) = config.token {
        return Ok(token);
    }
    let token = new_token()?;
    config.token = Some(token.clone());
    save(config_dir, &config)?;
    Ok(token)
}

fn new_token() -> io::Result<String> {
    let mut bytes = [0u8; 16];
    getrandom::fill(&mut bytes).map_err(|e| io::Error::other(e.to_string()))?;
    Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
}
