//! Caminho do arquivo de uma bolha recebida dentro da Pasta, para o drag
//! nativo do SO (SYN-006). Nunca aponta para fora da Pasta, mesmo com um nome
//! adulterado do lado JS: passa pelo mesmo `sanitize_file_name` que
//! `server::post_file` usa antes de gravar. O sufixo ` (1)` que o `post_file`
//! ainda acrescenta (`reserve_path`) já vem no nome do Item, então o nome que
//! chega aqui é o nome final do arquivo.

use std::path::{Path, PathBuf};

use crate::server::sanitize_file_name;

/// Caminho de `name` dentro de `pasta`, ou `None` se o arquivo não existe
/// mais lá (removido, movido) — a bolha então indica que sumiu, e o drag
/// nem começa.
pub fn resolve(pasta: &Path, name: &str) -> Option<PathBuf> {
    let caminho = pasta.join(sanitize_file_name(name));
    caminho.is_file().then_some(caminho)
}
