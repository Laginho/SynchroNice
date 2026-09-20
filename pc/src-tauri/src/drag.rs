//! Caminho do arquivo de uma bolha recebida dentro da Pasta, para o drag
//! nativo do SO (SYN-006). Nunca lê fora da Pasta, mesmo com um nome
//! adulterado do lado JS — reusa o mesmo saneamento de `server::post_file`.

use std::path::{Path, PathBuf};

use crate::server::sanitize_file_name;

/// Caminho de `name` dentro de `pasta`, ou `None` se o arquivo não existe
/// mais lá (removido, movido) — a bolha então indica que sumiu, e o drag
/// nem começa.
pub fn resolve(pasta: &Path, name: &str) -> Option<PathBuf> {
    let caminho = pasta.join(sanitize_file_name(name));
    caminho.is_file().then_some(caminho)
}
