//! O PC é o servidor. Serviço HTTP na rede local; o celular sempre inicia.
//!
//! Protocolo (SYN-002):
//!   POST /items/text        corpo: o texto, UTF-8
//!   Authorization: Bearer <token>
//!   201 sem corpo ao criar o Item; 401 sem token ou com token errado.
//!
//! Protocolo (SYN-003):
//!   POST /items/file        corpo: os bytes do arquivo, em stream
//!   Authorization: Bearer <token>
//!   X-File-Name: <nome>
//!   X-File-Size: <tamanho em bytes>    (opcional; usado só para o progresso)
//!   201 sem corpo ao gravar o arquivo; 401 sem token ou com token errado;
//!   503 se a Pasta ainda não foi escolhida.
//!
//! O id de um Item é do remetente (spec, "Protocolo de Itens"); como ele viaja
//! na requisição fica para o ticket que enfileira reenvios. Até lá o PC não
//! devolve id nenhum, para nenhum cliente nascer dependendo de um id do PC.

use crate::items::Items;
use axum::extract::{Request, State};
use axum::http::{HeaderMap, StatusCode};
use axum::routing::post;
use axum::Router;
use futures_util::StreamExt;
use std::io;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::mpsc;
use tokio::io::AsyncWriteExt;

/// Porta fixa na rede local. O celular a conhece pelo QR (SYN-005).
pub const PORT: u16 = 47820;

#[derive(Clone)]
struct AppState {
    token: String,
    items: Items,
    /// `None` até o usuário escolher a Pasta na primeira abertura do app.
    pasta: Option<PathBuf>,
}

/// Servidor no ar. Vive numa thread própria com seu runtime; some com o processo.
pub struct Running {
    pub addr: SocketAddr,
}

/// Sobe o servidor em `addr` (porta 0 escolhe uma livre) e devolve o endereço
/// real assim que estiver escutando.
pub fn spawn(
    addr: SocketAddr,
    token: String,
    items: Items,
    pasta: Option<PathBuf>,
) -> io::Result<Running> {
    let (tx, rx) = mpsc::channel::<io::Result<SocketAddr>>();

    std::thread::Builder::new()
        .name("synchronice-http".into())
        .spawn(move || {
            let runtime = match tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
            {
                Ok(rt) => rt,
                Err(e) => {
                    let _ = tx.send(Err(e));
                    return;
                }
            };
            runtime.block_on(async move {
                let listener = match tokio::net::TcpListener::bind(addr).await {
                    Ok(l) => l,
                    Err(e) => {
                        let _ = tx.send(Err(e));
                        return;
                    }
                };
                let local = listener.local_addr();
                let _ = tx.send(local);
                let app = router(AppState {
                    token,
                    items,
                    pasta,
                });
                if let Err(e) = axum::serve(listener, app).await {
                    eprintln!("servidor HTTP caiu: {e}");
                }
            });
        })?;

    let addr = rx
        .recv()
        .map_err(|_| io::Error::other("thread do servidor morreu antes de escutar"))??;
    Ok(Running { addr })
}

fn router(state: AppState) -> Router {
    Router::new()
        .route("/items/text", post(post_text))
        .route("/items/file", post(post_file))
        .with_state(state)
}

fn authorized(headers: &HeaderMap, token: &str) -> bool {
    headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .is_some_and(|t| t.trim() == token)
}

async fn post_text(State(state): State<AppState>, headers: HeaderMap, body: String) -> StatusCode {
    if !authorized(&headers, &state.token) {
        return StatusCode::UNAUTHORIZED;
    }
    state.items.push_text(body);
    StatusCode::CREATED
}

async fn post_file(State(state): State<AppState>, request: Request) -> StatusCode {
    if !authorized(request.headers(), &state.token) {
        return StatusCode::UNAUTHORIZED;
    }
    let Some(pasta) = state.pasta.clone() else {
        return StatusCode::SERVICE_UNAVAILABLE;
    };
    let Some(raw_name) = request
        .headers()
        .get("x-file-name")
        .and_then(|v| v.to_str().ok())
        .map(str::to_string)
    else {
        return StatusCode::BAD_REQUEST;
    };
    let size: u64 = request
        .headers()
        .get("x-file-size")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse().ok())
        .unwrap_or(0);

    let final_path = dedup_path(&pasta, &sanitize_file_name(&raw_name));
    let final_name = final_path
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("arquivo")
        .to_string();
    let temp_path = temp_path_for(&final_path);

    let mut file = match tokio::fs::File::create(&temp_path).await {
        Ok(f) => f,
        Err(e) => {
            eprintln!("não foi possível criar arquivo temporário {temp_path:?}: {e}");
            return StatusCode::INTERNAL_SERVER_ERROR;
        }
    };

    let item = state.items.start_file(final_name, size);

    let mut stream = request.into_body().into_data_stream();
    let mut received: u64 = 0;
    while let Some(chunk) = stream.next().await {
        let bytes = match chunk {
            Ok(bytes) => bytes,
            Err(_) => {
                // Conexão interrompida no meio: o arquivo fica só com o nome
                // temporário, nunca é renomeado para o nome final.
                return StatusCode::BAD_REQUEST;
            }
        };
        if let Err(e) = file.write_all(&bytes).await {
            eprintln!("falha ao gravar {temp_path:?}: {e}");
            return StatusCode::INTERNAL_SERVER_ERROR;
        }
        received += bytes.len() as u64;
        state.items.file_progress(item.id, received);
    }
    drop(file);

    if let Err(e) = tokio::fs::rename(&temp_path, &final_path).await {
        eprintln!("falha ao renomear {temp_path:?} para {final_path:?}: {e}");
        return StatusCode::INTERNAL_SERVER_ERROR;
    }

    StatusCode::CREATED
}

/// Reduz um nome de arquivo vindo da rede ao seu nome-base, sem caminho, e
/// troca por `_` qualquer caractere inválido em nome de arquivo no Windows.
/// Nunca deixa o resultado vazio nem ser só pontos (evita `.`/`..`).
pub(crate) fn sanitize_file_name(raw: &str) -> String {
    let base = raw.rsplit(['/', '\\']).next().unwrap_or("");
    let cleaned: String = base
        .chars()
        .map(|c| if is_windows_invalid(c) { '_' } else { c })
        .collect();
    let trimmed = cleaned.trim().trim_matches('.');
    if trimmed.is_empty() {
        "arquivo".to_string()
    } else {
        trimmed.to_string()
    }
}

fn is_windows_invalid(c: char) -> bool {
    matches!(c, '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*') || (c as u32) < 0x20
}

/// Separa nome e extensão para montar `nome (1).ext`. Sem extensão (ou nome
/// começando com `.`, como `.gitignore`), devolve `None` na extensão.
fn split_stem_ext(name: &str) -> (&str, Option<&str>) {
    match name.rsplit_once('.') {
        Some((stem, ext)) if !stem.is_empty() => (stem, Some(ext)),
        _ => (name, None),
    }
}

/// Caminho final dentro de `dir` para `name`, acrescentando ` (1)`, ` (2)`...
/// se já existir um arquivo com esse nome.
fn dedup_path(dir: &Path, name: &str) -> PathBuf {
    let candidate = dir.join(name);
    if !candidate.exists() {
        return candidate;
    }
    let (stem, ext) = split_stem_ext(name);
    let mut n = 1u32;
    loop {
        let attempt = match ext {
            Some(ext) => format!("{stem} ({n}).{ext}"),
            None => format!("{stem} ({n})"),
        };
        let path = dir.join(&attempt);
        if !path.exists() {
            return path;
        }
        n += 1;
    }
}

/// Nome temporário para gravar os bytes enquanto chegam: mesmo diretório do
/// destino final (para o rename ser atômico), sufixo aleatório para nunca
/// colidir com outro upload em andamento.
fn temp_path_for(final_path: &Path) -> PathBuf {
    let mut name = final_path
        .file_name()
        .map(|n| n.to_os_string())
        .unwrap_or_default();
    name.push(format!(".part-{}", random_suffix()));
    final_path.with_file_name(name)
}

fn random_suffix() -> String {
    let mut bytes = [0u8; 8];
    let _ = getrandom::fill(&mut bytes);
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}
