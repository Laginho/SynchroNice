use serde::{Deserialize, Serialize};
use std::io;
use std::path::{Path, PathBuf};

const FILE_NAME: &str = "config.json";

/// O perfil isolado não pode ler nem mudar o autostart da máquina.
pub fn autostart_permitido(perfil_isolado: bool) -> bool {
    !perfil_isolado
}

/// Resolve os dados e a permissão de acesso ao autostart, sem registrá-lo.
pub fn resolve_data_dir(
    default: PathBuf,
    override_dir: Option<std::ffi::OsString>,
) -> (PathBuf, bool) {
    let permitido = autostart_permitido(override_dir.is_some());
    (override_dir.map(PathBuf::from).unwrap_or(default), permitido)
}

/// Tudo que persiste além da Sessão: a Pasta e o Par (nome de instância e
/// token).
#[derive(Serialize, Deserialize, Default)]
struct ConfigFile {
    #[serde(default)]
    pasta: Option<PathBuf>,
    #[serde(default)]
    token: Option<String>,
    #[serde(default)]
    nome_instancia: Option<String>,
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

/// A Pasta escolhida, ou `None` se o app ainda não gravou uma preferência.
pub fn load_pasta(config_dir: &Path) -> Option<PathBuf> {
    load(config_dir).pasta
}

/// A Pasta gravada, ou `SynchroNice` dentro de Downloads quando ainda não há
/// uma preferência. O diretório de Downloads só é resolvido quando necessário.
pub fn pasta_efetiva<E>(
    config_dir: &Path,
    downloads_dir: impl FnOnce() -> Result<PathBuf, E>,
) -> Result<PathBuf, E> {
    match load_pasta(config_dir) {
        Some(pasta) => Ok(pasta),
        None => downloads_dir().map(|dir| dir.join("SynchroNice")),
    }
}

pub fn save_pasta(config_dir: &Path, pasta: &Path) -> io::Result<()> {
    let mut config = load(config_dir);
    config.pasta = Some(pasta.to_path_buf());
    save(config_dir, &config)
}

/// O token secreto do Par. Gerado na primeira chamada e gravado; depois,
/// sempre o mesmo. Parear de novo (SYN-005) é quem o troca, via `save_token`.
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

/// Sobrescreve o token gravado — "parear de novo" (SYN-005): o anterior
/// passa a ser recusado assim que o chamador atualizar seu próprio estado.
pub fn save_token(config_dir: &Path, token: &str) -> io::Result<()> {
    let mut config = load(config_dir);
    config.token = Some(token.to_string());
    save(config_dir, &config)
}

/// O nome de instância do Par, usado no QR e no anúncio mDNS. Gerado na
/// primeira chamada e gravado; depois, sempre o mesmo — estável por
/// instalação e sem informação pessoal (SYN-005).
pub fn load_or_create_nome_instancia(config_dir: &Path) -> io::Result<String> {
    let mut config = load(config_dir);
    if let Some(nome) = config.nome_instancia {
        return Ok(nome);
    }
    let nome = new_nome_instancia()?;
    config.nome_instancia = Some(nome.clone());
    save(config_dir, &config)?;
    Ok(nome)
}

/// Se já existe um Par gravado — usado só para decidir se a janela mostra o
/// QR no primeiro uso (SYN-005); a criação em si é `Par::load_or_create`.
pub fn par_ja_existe(config_dir: &Path) -> bool {
    load(config_dir).token.is_some()
}

pub(crate) fn new_token() -> io::Result<String> {
    let mut bytes = [0u8; 16];
    getrandom::fill(&mut bytes).map_err(|e| io::Error::other(e.to_string()))?;
    Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
}

/// Só letras minúsculas, dígitos e hífen: além de instância do QR, também
/// vira nome de host mDNS (`<nome>.local.`), que só aceita esse alfabeto.
fn new_nome_instancia() -> io::Result<String> {
    let mut bytes = [0u8; 4];
    getrandom::fill(&mut bytes).map_err(|e| io::Error::other(e.to_string()))?;
    let sufixo: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
    Ok(format!("synchronice-{sufixo}"))
}
