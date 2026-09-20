//! O PC é o servidor. Serviço HTTP na rede local; o celular sempre inicia.
//!
//! Protocolo (SYN-002):
//!   POST /items/text        corpo: o texto, UTF-8
//!   Authorization: Bearer <token>
//!   201 sem corpo ao criar o Item; 401 sem token ou com token errado.
//!
//! O id de um Item é do remetente (spec, "Protocolo de Itens"); como ele viaja
//! na requisição fica para o ticket que enfileira reenvios. Até lá o PC não
//! devolve id nenhum, para nenhum cliente nascer dependendo de um id do PC.

use crate::items::Items;
use axum::extract::State;
use axum::http::{HeaderMap, StatusCode};
use axum::routing::post;
use axum::Router;
use std::io;
use std::net::SocketAddr;
use std::sync::mpsc;

/// Porta fixa na rede local. O celular a conhece pelo QR (SYN-005).
pub const PORT: u16 = 47820;

#[derive(Clone)]
struct AppState {
    token: String,
    items: Items,
}

/// Servidor no ar. Vive numa thread própria com seu runtime; some com o processo.
pub struct Running {
    pub addr: SocketAddr,
}

/// Sobe o servidor em `addr` (porta 0 escolhe uma livre) e devolve o endereço
/// real assim que estiver escutando.
pub fn spawn(addr: SocketAddr, token: String, items: Items) -> io::Result<Running> {
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
                let app = router(AppState { token, items });
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
