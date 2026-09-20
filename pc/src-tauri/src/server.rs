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
//!   X-File-Size: <tamanho em bytes>    (só quando o corpo vier chunked, sem
//!                                       Content-Length; um POST comum de
//!                                       corpo cru — o `curl` do bloco
//!                                       Verification deste ticket, ou o que
//!                                       o Android vai mandar — não precisa
//!                                       deste cabeçalho, o tamanho vem do
//!                                       Content-Length)
//!   201 sem corpo ao gravar o arquivo; 401 sem token ou com token errado;
//!   503 se a Pasta ainda não foi escolhida.
//!
//! Protocolo (SYN-004): direção PC -> celular. O PC é o remetente e gera o
//! id do Item (spec, "Protocolo de Itens"); só a confirmação do celular
//! remove o Item da fila de Pendentes, para reenvio nunca duplicar bolha nem
//! arquivo do outro lado.
//!
//!   GET  /listen                 bloqueia até haver ao menos um Pendente,
//!                                 devolve os Pendentes de agora, um por
//!                                 linha (NDJSON), e fecha; o celular reabre
//!                                 para continuar escutando. A conexão viva
//!                                 é o sinal de presença.
//!   Authorization: Bearer <token>
//!
//!   GET  /outbox/{id}/file       bytes de um Item de arquivo ainda
//!                                 Pendente; 404 se o id não existir ou já
//!                                 ter sido confirmado.
//!   Authorization: Bearer <token>
//!
//!   POST /outbox/{id}/ack        confirma o recebimento do Item `id` — só
//!                                 isso o remove da fila de Pendentes; 204
//!                                 sempre (mesmo se o id já não existir mais,
//!                                 para reenvio da confirmação ser inofensivo).
//!   Authorization: Bearer <token>
//!
//! Pareamento e descoberta (SYN-005). O celular não compila do mesmo fonte,
//! então os dois formatos abaixo são contrato (ADR-0001):
//!
//!   QR mostrado pelo PC     JSON de uma linha, exatamente estes campos:
//!                           {"instance_name":"synchronice-<8 hex>",
//!                            "port":47820,"token":"<32 hex>"}
//!   Anúncio mDNS            serviço `_synchronice._tcp.local.`, nome de
//!                           instância igual ao `instance_name` do QR, host
//!                           `<instance_name>.local.`, porta igual à do QR.
//!
//! O celular resolve o nome de instância por mDNS para achar o IP atual do PC
//! e manda o token em `Authorization: Bearer` em toda requisição acima.
//! Parear de novo troca só o token; o nome de instância é estável por
//! instalação.

use crate::items::Items;
use crate::outbox::{OutItem, Outbox};
use axum::extract::{Path as AxPath, Request, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::IntoResponse;
use axum::routing::{get, post};
use axum::Router;
use futures_util::StreamExt;
use std::collections::HashSet;
use std::io;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::{mpsc, Arc, Mutex};
use tokio::io::AsyncWriteExt;

/// Porta fixa na rede local. O celular a conhece pelo QR (SYN-005).
pub const PORT: u16 = 47820;

/// Fonte do token que autentica as requisições: um `String` fixo (a maioria
/// dos testes de integração) ou um `Par` (SYN-005). Vindo de um `Par`,
/// compartilha a mesma célula — rotacionar o Par já vale para a próxima
/// requisição, sem reiniciar o servidor.
#[derive(Clone)]
pub struct TokenCell(Arc<Mutex<String>>);

impl From<String> for TokenCell {
    fn from(token: String) -> Self {
        TokenCell(Arc::new(Mutex::new(token)))
    }
}

impl From<crate::par::Par> for TokenCell {
    fn from(par: crate::par::Par) -> Self {
        TokenCell(par.shared_token())
    }
}

impl TokenCell {
    fn get(&self) -> String {
        self.0.lock().expect("token sem panic").clone()
    }
}

#[derive(Clone)]
struct AppState {
    token: TokenCell,
    items: Items,
    outbox: Outbox,
    presence: Presence,
    /// `None` até o usuário escolher a Pasta na primeira abertura do app.
    pasta: Option<PathBuf>,
    /// Nomes finais já reservados por um envio em andamento, mas ainda não
    /// gravados na Pasta — sem isso, dois envios concorrentes do mesmo nome
    /// podem ver `exists()` igualmente falso e resolver para o mesmo destino.
    leases: Arc<Mutex<HashSet<PathBuf>>>,
}

/// Se há uma conexão `/listen` viva agora — é o que a spec chama de "o outro
/// lado está na rede". Um contador, não um booleano: uma segunda conexão
/// enquanto a primeira ainda não fechou não pode fazer a desconexão dela
/// apagar a presença por engano.
#[derive(Clone, Default)]
pub struct Presence {
    inner: Arc<Mutex<PresenceInner>>,
}

#[derive(Default)]
struct PresenceInner {
    count: u32,
    listeners: Vec<Box<dyn Fn(bool) + Send + Sync>>,
}

impl Presence {
    pub fn get(&self) -> bool {
        self.inner.lock().expect("presença sem panic").count > 0
    }

    /// Chamado a cada vez que a presença muda (de ausente para presente ou
    /// vice-versa), na thread que fechou ou abriu a conexão `/listen`.
    pub fn on_change(&self, listener: impl Fn(bool) + Send + Sync + 'static) {
        self.inner
            .lock()
            .expect("presença sem panic")
            .listeners
            .push(Box::new(listener));
    }

    /// Marca uma conexão `/listen` como viva; solta a presença sozinha
    /// quando o guarda sair de escopo (a conexão fechar).
    fn enter(&self) -> PresenceGuard {
        let mut inner = self.inner.lock().expect("presença sem panic");
        inner.count += 1;
        if inner.count == 1 {
            for listener in &inner.listeners {
                listener(true);
            }
        }
        PresenceGuard {
            presence: self.clone(),
        }
    }

    fn leave(&self) {
        let mut inner = self.inner.lock().expect("presença sem panic");
        inner.count -= 1;
        if inner.count == 0 {
            for listener in &inner.listeners {
                listener(false);
            }
        }
    }
}

struct PresenceGuard {
    presence: Presence,
}

impl Drop for PresenceGuard {
    fn drop(&mut self) {
        self.presence.leave();
    }
}

/// Servidor no ar. Vive numa thread própria com seu runtime; some com o processo.
pub struct Running {
    pub addr: SocketAddr,
    /// Alça para o sinal de presença — quem sobe o servidor usa
    /// `on_change` para avisar a janela quando o celular conecta ou some.
    pub presence: Presence,
}

/// Sobe o servidor em `addr` (porta 0 escolhe uma livre) e devolve o endereço
/// real assim que estiver escutando.
pub fn spawn(
    addr: SocketAddr,
    token: impl Into<TokenCell>,
    items: Items,
    outbox: Outbox,
    pasta: Option<PathBuf>,
) -> io::Result<Running> {
    let token = token.into();
    let (tx, rx) = mpsc::channel::<io::Result<SocketAddr>>();
    let presence = Presence::default();
    let presence_da_thread = presence.clone();

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
                    outbox,
                    presence: presence_da_thread,
                    pasta,
                    leases: Arc::new(Mutex::new(HashSet::new())),
                });
                if let Err(e) = axum::serve(listener, app).await {
                    eprintln!("servidor HTTP caiu: {e}");
                }
            });
        })?;

    let addr = rx
        .recv()
        .map_err(|_| io::Error::other("thread do servidor morreu antes de escutar"))??;
    Ok(Running { addr, presence })
}

fn router(state: AppState) -> Router {
    Router::new()
        .route("/items/text", post(post_text))
        .route("/items/file", post(post_file))
        .route("/listen", get(get_listen))
        .route("/outbox/{id}/file", get(get_outbox_file))
        .route("/outbox/{id}/ack", post(post_outbox_ack))
        .with_state(state)
}

fn authorized(headers: &HeaderMap, token: &TokenCell) -> bool {
    headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .is_some_and(|t| t.trim() == token.get())
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
    // Content-Length primeiro: é o tamanho real que o corpo vai entregar.
    // X-File-Size só entra para um corpo chunked, sem Content-Length (ex.:
    // um provider do Android que não sabe o tamanho de antemão).
    let size: u64 = request
        .headers()
        .get("content-length")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse().ok())
        .or_else(|| {
            request
                .headers()
                .get("x-file-size")
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.parse().ok())
        })
        .unwrap_or(0);

    let final_path = reserve_path(&pasta, &sanitize_file_name(&raw_name), &state.leases);
    let _lease = LeaseGuard {
        leases: state.leases.clone(),
        path: final_path.clone(),
    };
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
                // temporário, nunca é renomeado para o nome final, e a bolha
                // nunca recebe o sinal de fim.
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

    // Rename e aviso de fim rodam numa tarefa própria a partir daqui: se a
    // conexão cair logo depois do último byte do corpo (o celular não
    // precisa ficar esperando a resposta), a future desta requisição é
    // cancelada, mas uma tarefa spawnada não é abortada por isso — sem essa
    // separação o rename já tinha acontecido e o aviso de fim nunca saía.
    let items = state.items.clone();
    let final_path_bg = final_path.clone();
    let temp_path_bg = temp_path.clone();
    let concluido = tokio::spawn(async move {
        // Move a reserva do nome para dentro da tarefa: ela só é liberada
        // depois do rename, nunca enquanto ele ainda está em voo.
        let _lease = _lease;
        match tokio::fs::rename(&temp_path_bg, &final_path_bg).await {
            Ok(()) => {
                // Só agora o arquivo existe com o nome final: o sinal de fim
                // não pode vir antes disso, ou a UI tentaria ler um caminho
                // que ainda não existe.
                items.file_done(item.id);
                true
            }
            Err(e) => {
                eprintln!("falha ao renomear {temp_path_bg:?} para {final_path_bg:?}: {e}");
                false
            }
        }
    })
    .await
    .unwrap_or_else(|e| {
        eprintln!("tarefa de conclusão de {final_path:?} não terminou: {e}");
        false
    });

    if concluido {
        StatusCode::CREATED
    } else {
        StatusCode::INTERNAL_SERVER_ERROR
    }
}

/// Bloqueia até a fila de saída ter ao menos um Pendente, devolve todos os
/// que houver então (um por linha) e fecha — long-poll clássico: o celular
/// reabre para continuar escutando. A conexão viva, do início ao fim desta
/// função, é o sinal de presença.
async fn get_listen(State(state): State<AppState>, headers: HeaderMap) -> impl IntoResponse {
    if !authorized(&headers, &state.token) {
        return StatusCode::UNAUTHORIZED.into_response();
    }
    let _presenca = state.presence.enter();
    let (pendentes, mut novos) = state.outbox.subscribe();
    let itens: Vec<OutItem> = if !pendentes.is_empty() {
        pendentes
    } else {
        match novos.recv().await {
            Some(item) => vec![item],
            None => Vec::new(),
        }
    };

    let corpo = itens
        .iter()
        .map(|item| serde_json::to_string(item).expect("OutItem serializa"))
        .collect::<Vec<_>>()
        .join("\n");
    (StatusCode::OK, corpo).into_response()
}

/// Bytes de um Item de arquivo ainda Pendente na fila de saída.
async fn get_outbox_file(
    State(state): State<AppState>,
    headers: HeaderMap,
    AxPath(id): AxPath<u64>,
) -> impl IntoResponse {
    if !authorized(&headers, &state.token) {
        return StatusCode::UNAUTHORIZED.into_response();
    }
    match state.outbox.file_bytes(id) {
        Ok(bytes) => (StatusCode::OK, bytes).into_response(),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => StatusCode::NOT_FOUND.into_response(),
        Err(e) => {
            eprintln!("falha ao ler o arquivo de saída do Item {id}: {e}");
            StatusCode::INTERNAL_SERVER_ERROR.into_response()
        }
    }
}

/// Confirma o recebimento do Item `id`: só isso o remove da fila de
/// Pendentes. 204 mesmo se o id já não existir mais — reenviar a confirmação
/// depois de uma resposta perdida na rede tem que ser inofensivo.
async fn post_outbox_ack(
    State(state): State<AppState>,
    headers: HeaderMap,
    AxPath(id): AxPath<u64>,
) -> StatusCode {
    if !authorized(&headers, &state.token) {
        return StatusCode::UNAUTHORIZED;
    }
    state.outbox.confirm(id);
    StatusCode::NO_CONTENT
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
/// se já existir um arquivo com esse nome ou já estar reservado por outro
/// envio em andamento. Reserva o caminho escolhido em `leases` antes de
/// devolver — sob o mesmo lock que checa `exists()`, para dois envios
/// concorrentes do mesmo nome nunca escolherem o mesmo destino: sem isso,
/// nenhum dos dois vê o outro em `exists()` até o `rename()` do fim, que só
/// acontece depois de todos os bytes chegarem.
fn reserve_path(dir: &Path, name: &str, leases: &Mutex<HashSet<PathBuf>>) -> PathBuf {
    let mut leased = leases.lock().expect("leases sem panic");
    let (stem, ext) = split_stem_ext(name);
    let mut n = 0u32;
    loop {
        let candidate = if n == 0 {
            dir.join(name)
        } else {
            let attempt = match ext {
                Some(ext) => format!("{stem} ({n}).{ext}"),
                None => format!("{stem} ({n})"),
            };
            dir.join(&attempt)
        };
        if !candidate.exists() && !leased.contains(&candidate) {
            leased.insert(candidate.clone());
            return candidate;
        }
        n += 1;
    }
}

/// Libera, ao sair de escopo, o nome que `reserve_path` reservou — em todo
/// retorno de `post_file`, sucesso ou erro, para um envio cortado não deixar
/// o nome preso para sempre. Depois que o corpo chega inteiro o guarda passa
/// para a tarefa que faz o rename, então a reserva só cai quando o arquivo já
/// está no nome final (ou o rename falhou).
struct LeaseGuard {
    leases: Arc<Mutex<HashSet<PathBuf>>>,
    path: PathBuf,
}

impl Drop for LeaseGuard {
    fn drop(&mut self) {
        self.leases
            .lock()
            .expect("leases sem panic")
            .remove(&self.path);
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
