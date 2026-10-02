//! O PC é o servidor. Serviço HTTP na rede local; o celular sempre inicia.
//!
//! Protocolo (SYN-002):
//!   POST /items/text        corpo: o texto, UTF-8
//!   Authorization: Bearer <token>
//!   X-Item-Id: <id gerado pelo remetente> (opcional para clientes antigos)
//!   201 sem corpo ao criar o Item; 401 sem token ou com token errado.
//!   Um id já aceito responde 201 sem criar outro Item: é o reenvio de uma
//!   resposta perdida. Id vazio, com mais de 128 bytes ou fora de ASCII
//!   visível: 400.
//!
//! Protocolo (SYN-003):
//!   POST /items/file        corpo: os bytes do arquivo, em stream
//!   Authorization: Bearer <token>
//!   X-Item-Id: <id gerado pelo remetente> (opcional para clientes antigos)
//!   X-File-Name-Utf8: <nome UTF-8 percent-encoded, espaço = %20>
//!   X-File-Name: <nome ASCII>       fallback legado; '%' é literal aqui
//!   X-File-Size: <tamanho em bytes>    (só quando o corpo vier chunked, sem
//!                                       Content-Length; um POST comum de
//!                                       corpo cru — o `curl` do bloco
//!                                       Verification deste ticket, ou o que
//!                                       o Android vai mandar — não precisa
//!                                       deste cabeçalho, o tamanho vem do
//!                                       Content-Length)
//!   201 sem corpo ao gravar o arquivo; 401 sem token ou com token errado;
//!   503 se a Pasta ainda não foi escolhida. Um id já aceito responde 201 sem
//!   outro Item nem outro arquivo, também depois de o PC reiniciar (os ids de
//!   arquivo ficam em disco); id inválido é 400, como no texto. 400 também
//!   quando o corpo fica 30 s sem nenhum byte (o lock do id é solto), chega
//!   incompleto ou passa do tamanho declarado.
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
//!   HEAD /listen                 200 se há presença (um GET /listen ou um
//!                                 download autenticado ativo); 204 se não
//!                                 há; 401 com token inválido.
//!   Authorization: Bearer <token>
//!
//!   GET  /outbox/{id}/file       bytes de um Item de arquivo ainda
//!                                 Pendente; 404 se o id não existir ou já
//!                                 ter sido confirmado. Enquanto o corpo está
//!                                 saindo, a conexão também é presença
//!                                 (CLEAN-015). HEAD responde os mesmos
//!                                 cabeçalhos sem corpo e sem presença.
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
//!                            "port":47820,"token":"<32 hex>",
//!                            "ip":"<IPv4 do PC>"}
//!                           `ip` é opcional: omitido se o PC não achou rota
//!                           para a LAN, e ausente nos QRs antigos (continuam
//!                           válidos). O celular o guarda como `last-address`
//!                           inicial; o mDNS, se resolver, sobrescreve.
//!   Anúncio mDNS            serviço `_synchronice._tcp.local.`, nome de
//!                           instância igual ao `instance_name` do QR, host
//!                           `<instance_name>.local.`, porta igual à do QR.
//!
//! O celular resolve o nome de instância por mDNS para achar o IP atual do PC
//! e manda o token em `Authorization: Bearer` em toda requisição acima.
//! Parear de novo troca só o token; o nome de instância é estável por
//! instalação.

use crate::items::{Item, ItemBody, Items};
use crate::outbox::{OutItem, Outbox};
use axum::extract::{Path as AxPath, Request, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::IntoResponse;
use axum::routing::{get, post};
use axum::Router;
use futures_util::StreamExt;
use std::collections::{HashMap, HashSet, VecDeque};
use std::io::{self, Write};
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::{mpsc, Arc, Mutex};
use std::time::Duration;
use tokio::io::AsyncWriteExt;

/// Porta fixa na rede local. O celular a conhece pelo QR (SYN-005).
pub const PORT: u16 = 47820;

/// Tempo sem receber nenhum byte do corpo de um upload até abortá-lo como
/// corpo incompleto e soltar o lock do id — sem isso, uma conexão que morre
/// no meio do arquivo prende o lock para sempre.
const UPLOAD_INACTIVITY_TIMEOUT: Duration = Duration::from_secs(30);

/// ponytail: só os últimos 1000 ids de arquivo aceitos; o celular reenvia o
/// que ainda está na própria fila logo depois, não semanas depois.
const ACCEPTED_FILE_IDS_KEPT: usize = 1000;

/// Ids de arquivo aceitos, também em disco (ADR-0002): a Sessão e os ids de
/// texto somem com o processo, mas o arquivo fica na Pasta, e o reenvio de um
/// 201 que se perdeu antes de o PC reiniciar viraria `nome (1)`.
struct AcceptedFileLog {
    path: PathBuf,
    recent: VecDeque<String>,
}

impl AcceptedFileLog {
    fn load(path: &Path) -> io::Result<Self> {
        let recent = match std::fs::read_to_string(path) {
            Ok(text) => text.lines().map(str::to_string).collect(),
            Err(e) if e.kind() == io::ErrorKind::NotFound => VecDeque::new(),
            Err(e) => return Err(e),
        };
        Ok(AcceptedFileLog {
            path: path.to_path_buf(),
            recent,
        })
    }

    /// Falhar aqui não recusa o arquivo, que já está na Pasta: no pior caso,
    /// o reenvio depois de um reinício duplica, como antes deste registro.
    fn record(&mut self, id: &str) {
        self.recent.push_back(id.to_string());
        let result = if self.recent.len() > 2 * ACCEPTED_FILE_IDS_KEPT {
            let excess = self.recent.len() - ACCEPTED_FILE_IDS_KEPT;
            self.recent.drain(..excess);
            let temp = self.path.with_extension("tmp");
            let text: String = self.recent.iter().map(|id| format!("{id}\n")).collect();
            std::fs::write(&temp, text).and_then(|()| std::fs::rename(&temp, &self.path))
        } else {
            std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(&self.path)
                .and_then(|mut file| writeln!(file, "{id}"))
        };
        if let Err(e) = result {
            eprintln!("não foi possível guardar o id de arquivo aceito {id}: {e}");
        }
    }
}

/// Fonte do token que autentica as requisições: um `String` fixo (a maioria
/// dos testes de integração) ou um `Par` (SYN-005). Vindo de um `Par`,
/// compartilha a mesma célula — rotacionar o Par já vale para a próxima
/// requisição, sem reiniciar o servidor.
#[derive(Clone)]
pub struct TokenCell(tokio::sync::watch::Sender<String>);

impl From<String> for TokenCell {
    fn from(token: String) -> Self {
        TokenCell(tokio::sync::watch::channel(token).0)
    }
}

impl From<crate::par::Par> for TokenCell {
    fn from(par: crate::par::Par) -> Self {
        TokenCell(par.shared_token())
    }
}

impl TokenCell {
    fn get(&self) -> String {
        self.0.borrow().clone()
    }
}

#[derive(Clone)]
struct AppState {
    token: TokenCell,
    items: Items,
    outbox: Outbox,
    presence: Presence,
    /// `None` quando quem sobe o servidor ainda não resolveu uma Pasta.
    pasta: Arc<Mutex<Option<PathBuf>>>,
    /// Nomes finais já reservados por um envio em andamento, mas ainda não
    /// gravados na Pasta — sem isso, dois envios concorrentes do mesmo nome
    /// podem ver `exists()` igualmente falso e resolver para o mesmo destino.
    leases: Arc<Mutex<HashSet<PathBuf>>>,
    /// Caminho de cada arquivo recebido que terminou de gravar, indexado pelo
    /// id do Item para que Itens antigos continuem apontando à Pasta original.
    received_files: Arc<Mutex<HashMap<u64, PathBuf>>>,
    /// Ids que este processo já aceitou do remetente. A Sessão é em memória,
    /// portanto a deduplicação tem o mesmo ciclo de vida dos Itens visíveis.
    accepted_item_ids: Arc<Mutex<HashSet<String>>>,
    /// `None` nos testes que não reiniciam o PC; o app sempre guarda.
    accepted_file_log: Option<Arc<Mutex<AcceptedFileLog>>>,
    /// ponytail: um lock global basta para os uploads com id — o timeout de
    /// inatividade da leitura do corpo garante que o pior caso é esperar o
    /// upload anterior estourar o timeout, nunca travar para sempre;
    /// particionar por id se isso virar gargalo mensurável.
    file_item_id_lock: Arc<tokio::sync::Mutex<()>>,
    upload_inactivity_timeout: Duration,
    #[cfg(test)]
    before_file_item_id_lock: Arc<Mutex<Option<TestPause>>>,
    #[cfg(test)]
    after_file_rename: Arc<Mutex<Option<TestPause>>>,
}

#[cfg(test)]
#[derive(Clone)]
struct TestPause {
    reached: Arc<tokio::sync::Notify>,
    resume: Arc<tokio::sync::Notify>,
}

#[cfg(test)]
async fn pause_once(slot: &Mutex<Option<TestPause>>) {
    let pause = slot.lock().expect("pausa de teste sem panic").take();
    if let Some(pause) = pause {
        pause.reached.notify_one();
        pause.resume.notified().await;
    }
}

/// Se há uma conexão viva agora com a fila de saída — `/listen` ou um
/// download de `/outbox/{id}/file` em andamento — é o que a spec chama de "o
/// outro lado está na rede" (CLEAN-015). Um contador, não um booleano: uma
/// segunda conexão enquanto a primeira ainda não fechou não pode fazer a
/// desconexão dela apagar a presença por engano.
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
    /// vice-versa), na thread que fechou ou abriu a conexão (`/listen` ou
    /// download).
    pub fn on_change(&self, listener: impl Fn(bool) + Send + Sync + 'static) {
        self.inner
            .lock()
            .expect("presença sem panic")
            .listeners
            .push(Box::new(listener));
    }

    /// Marca uma conexão (`/listen` ou download) como viva; solta a presença
    /// sozinha quando o guarda sair de escopo (a conexão fechar).
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
    pasta: Arc<Mutex<Option<PathBuf>>>,
    received_files: Arc<Mutex<HashMap<u64, PathBuf>>>,
}

impl Running {
    /// Persiste a escolha e a aplica ao servidor que já está escutando.
    pub fn escolher_pasta(&self, config_dir: &Path, pasta: &Path) -> io::Result<()> {
        crate::config::save_pasta(config_dir, pasta)?;
        *self.pasta.lock().expect("Pasta sem panic") = Some(pasta.to_path_buf());
        Ok(())
    }

    /// Caminho do arquivo recebido que pertence ao Item `id`, se ele chegou
    /// a terminar de gravar. O caminho não depende da Pasta atualmente ativa.
    pub fn caminho_do_arquivo(&self, id: u64) -> Option<PathBuf> {
        self.received_files
            .lock()
            .expect("arquivos recebidos sem panic")
            .get(&id)
            .cloned()
    }
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
    spawn_inner(
        addr,
        token,
        items,
        outbox,
        pasta,
        UPLOAD_INACTIVITY_TIMEOUT,
        None,
    )
}

/// Como `spawn`, guardando em `accepted_ids_file` os ids de arquivo aceitos,
/// para o reenvio depois de um reinício do PC não duplicar o arquivo na Pasta.
/// É o que o app usa.
pub fn spawn_with_accepted_ids(
    addr: SocketAddr,
    token: impl Into<TokenCell>,
    items: Items,
    outbox: Outbox,
    pasta: Option<PathBuf>,
    accepted_ids_file: &Path,
) -> io::Result<Running> {
    spawn_inner(
        addr,
        token,
        items,
        outbox,
        pasta,
        UPLOAD_INACTIVITY_TIMEOUT,
        Some(accepted_ids_file),
    )
}

/// Como `spawn`, mas com o timeout de inatividade do upload configurável —
/// só para os testes de integração encurtarem sem esperar os 30s de verdade.
pub fn spawn_with_upload_timeout(
    addr: SocketAddr,
    token: impl Into<TokenCell>,
    items: Items,
    outbox: Outbox,
    pasta: Option<PathBuf>,
    upload_inactivity_timeout: Duration,
) -> io::Result<Running> {
    spawn_inner(
        addr,
        token,
        items,
        outbox,
        pasta,
        upload_inactivity_timeout,
        None,
    )
}

fn spawn_inner(
    addr: SocketAddr,
    token: impl Into<TokenCell>,
    items: Items,
    outbox: Outbox,
    pasta: Option<PathBuf>,
    upload_inactivity_timeout: Duration,
    accepted_ids_file: Option<&Path>,
) -> io::Result<Running> {
    let accepted_file_log = accepted_ids_file.map(AcceptedFileLog::load).transpose()?;
    let accepted_item_ids: HashSet<String> = accepted_file_log
        .iter()
        .flat_map(|log| log.recent.iter().cloned())
        .collect();
    let accepted_file_log = accepted_file_log.map(|log| Arc::new(Mutex::new(log)));
    let token = token.into();
    let (tx, rx) = mpsc::channel::<io::Result<SocketAddr>>();
    let presence = Presence::default();
    let presence_da_thread = presence.clone();
    let pasta = Arc::new(Mutex::new(pasta));
    let pasta_da_thread = pasta.clone();
    let received_files = Arc::new(Mutex::new(HashMap::new()));
    let received_files_da_thread = received_files.clone();

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
                    pasta: pasta_da_thread,
                    leases: Arc::new(Mutex::new(HashSet::new())),
                    received_files: received_files_da_thread,
                    accepted_item_ids: Arc::new(Mutex::new(accepted_item_ids)),
                    accepted_file_log,
                    file_item_id_lock: Arc::new(tokio::sync::Mutex::new(())),
                    upload_inactivity_timeout,
                    #[cfg(test)]
                    before_file_item_id_lock: Arc::new(Mutex::new(None)),
                    #[cfg(test)]
                    after_file_rename: Arc::new(Mutex::new(None)),
                });
                if let Err(e) = axum::serve(listener, app).await {
                    eprintln!("servidor HTTP caiu: {e}");
                }
            });
        })?;

    let addr = rx
        .recv()
        .map_err(|_| io::Error::other("thread do servidor morreu antes de escutar"))??;
    Ok(Running {
        addr,
        presence,
        pasta,
        received_files,
    })
}

fn router(state: AppState) -> Router {
    Router::new()
        .route("/items/text", post(post_text))
        .route("/items/file", post(post_file))
        .route("/items/delete", post(post_delete))
        .route("/listen", get(get_listen).head(head_listen))
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

fn item_id(headers: &HeaderMap) -> Result<Option<String>, StatusCode> {
    let Some(value) = headers.get("x-item-id") else {
        return Ok(None);
    };
    let id = value.to_str().map_err(|_| StatusCode::BAD_REQUEST)?;
    if id.is_empty() || id.len() > 128 {
        return Err(StatusCode::BAD_REQUEST);
    }
    Ok(Some(id.to_string()))
}

async fn post_text(State(state): State<AppState>, headers: HeaderMap, body: String) -> StatusCode {
    if !authorized(&headers, &state.token) {
        return StatusCode::UNAUTHORIZED;
    }
    let item_id = match item_id(&headers) {
        Ok(id) => id,
        Err(status) => return status,
    };
    let mut accepted = state
        .accepted_item_ids
        .lock()
        .expect("ids aceitos sem panic");
    if item_id.as_ref().is_some_and(|id| accepted.contains(id)) {
        return StatusCode::CREATED;
    }
    state.items.push_text_with_sender_id(body, item_id.clone());
    if let Some(id) = item_id {
        accepted.insert(id);
    }
    StatusCode::CREATED
}

#[derive(serde::Deserialize)]
struct DeleteRequest {
    do_pc: Vec<u64>,
    do_celular: Vec<String>,
}

async fn post_delete(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: String,
) -> StatusCode {
    if !authorized(&headers, &state.token) {
        return StatusCode::UNAUTHORIZED;
    }
    let Ok(request) = serde_json::from_str::<DeleteRequest>(&body) else {
        return StatusCode::BAD_REQUEST;
    };
    match tokio::task::spawn_blocking(move || apply_delete(&state, request)).await {
        Ok(Ok(())) => StatusCode::NO_CONTENT,
        result => {
            eprintln!("não foi possível aplicar a exclusão: {result:?}");
            StatusCode::INTERNAL_SERVER_ERROR
        }
    }
}

fn apply_delete(state: &AppState, request: DeleteRequest) -> io::Result<()> {
    // O mesmo lock protege o registro do caminho e a conclusão do upload:
    // a exclusão vê o arquivo completo ou fica marcada para o seu término.
    let mut files = state
        .received_files
        .lock()
        .expect("arquivos recebidos sem panic");
    let mut accepted = state
        .accepted_item_ids
        .lock()
        .expect("ids aceitos sem panic");
    let requested: HashSet<String> = request.do_celular.into_iter().collect();
    let received: Vec<Item> = state
        .items
        .snapshot()
        .into_iter()
        .filter(|item| {
            item.sender_id
                .as_ref()
                .is_some_and(|id| requested.contains(id))
        })
        .collect();
    state.outbox.forget(&request.do_pc)?;
    for item in &received {
        if let Some(id) = &item.sender_id {
            // Mesmo um upload que falhe depois do pedido não pode ressuscitar
            // por um retry. Só ids de Itens conhecidos entram aqui.
            accepted.insert(id.clone());
        }
    }
    drop(accepted);
    for item in &received {
        recycle_received_file(&files, item);
        files.remove(&item.id);
    }
    state
        .items
        .forget(&received.iter().map(|item| item.id).collect::<Vec<_>>());
    Ok(())
}

fn recycle_received_file(files: &HashMap<u64, PathBuf>, item: &Item) {
    let (Some(path), ItemBody::File { received, .. }) = (files.get(&item.id), &item.body) else {
        return;
    };
    let Ok(metadata) = std::fs::symlink_metadata(path) else {
        return;
    };
    if !metadata.is_file() || metadata.len() != *received {
        return;
    }
    let Ok(canonical) = path.canonicalize() else {
        return;
    };
    // canonicalize também compara grafias diferentes do mesmo caminho no Windows.
    if files.iter().any(|(id, newer)| {
        *id > item.id && newer.canonicalize().is_ok_and(|path| path == canonical)
    }) {
        return;
    }
    if let Err(error) = trash::delete(path) {
        eprintln!("não foi possível mover {path:?} para a Lixeira: {error}");
    }
}

async fn post_file(State(state): State<AppState>, request: Request) -> StatusCode {
    if !authorized(request.headers(), &state.token) {
        return StatusCode::UNAUTHORIZED;
    }
    let item_id = match item_id(request.headers()) {
        Ok(id) => id,
        Err(status) => return status,
    };
    #[cfg(test)]
    pause_once(&state.before_file_item_id_lock).await;
    let item_id_lock = if item_id.is_some() {
        Some(state.file_item_id_lock.clone().lock_owned().await)
    } else {
        None
    };
    if item_id.as_ref().is_some_and(|id| {
        state
            .accepted_item_ids
            .lock()
            .expect("ids aceitos sem panic")
            .contains(id)
    }) {
        return StatusCode::CREATED;
    }
    let Some(pasta) = state.pasta.lock().expect("Pasta sem panic").clone() else {
        return StatusCode::SERVICE_UNAVAILABLE;
    };
    let raw_name = if let Some(encoded) = request.headers().get("x-file-name-utf8") {
        encoded.to_str().ok().and_then(|name| {
            percent_encoding::percent_decode_str(name)
                .decode_utf8()
                .ok()
                .map(|s| s.into_owned())
        })
    } else {
        request
            .headers()
            .get("x-file-name")
            .and_then(|v| v.to_str().ok())
            .map(str::to_string)
    };
    let Some(raw_name) = raw_name else {
        return StatusCode::BAD_REQUEST;
    };
    // Content-Length primeiro: é o tamanho real que o corpo vai entregar.
    // X-File-Size só entra para um corpo chunked, sem Content-Length (ex.:
    // um provider do Android que não sabe o tamanho de antemão).
    let content_length = request.headers().get("content-length");
    let declared_size = content_length.or_else(|| request.headers().get("x-file-size"));
    let expected_size = match declared_size {
        Some(value) => match value.to_str().ok().and_then(|v| v.parse::<u64>().ok()) {
            Some(size) => Some(size),
            None => return StatusCode::BAD_REQUEST,
        },
        None => None,
    };
    let fixed_length = content_length.is_some();
    let size = expected_size.unwrap_or(0);

    if let Err(e) = tokio::fs::create_dir_all(&pasta).await {
        eprintln!("não foi possível criar a Pasta {pasta:?}: {e}");
        return StatusCode::INTERNAL_SERVER_ERROR;
    }

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

    // O guarda é declarado antes do handle de propósito: a ordem de destruição
    // é a inversa da declaração, então o `file` fecha primeiro e o
    // `remove_file` do guarda encontra o `.part` sem nenhum handle aberto.
    // Ao contrário, o unlink dependeria de o handle ter sido aberto com
    // compartilhamento de exclusão e de o sistema de arquivos aceitar apagar
    // um arquivo aberto — a Pasta é escolhida pelo usuário e pode estar em
    // exFAT ou num share de rede.
    let item = {
        let accepted = state
            .accepted_item_ids
            .lock()
            .expect("ids aceitos sem panic");
        if item_id.as_ref().is_some_and(|id| accepted.contains(id)) {
            return StatusCode::CREATED;
        }
        state
            .items
            .start_file_with_sender_id(final_name, size, item_id.clone())
    };
    let fail_guard = FailGuard {
        items: state.items.clone(),
        item_id: item.id,
        temp_path: temp_path.clone(),
    };

    let mut file = match tokio::fs::File::create(&temp_path).await {
        Ok(f) => f,
        Err(e) => {
            eprintln!("não foi possível criar arquivo temporário {temp_path:?}: {e}");
            // fail_guard marca o Item como failed ao sair de escopo: um envio
            // que morre antes do primeiro byte também não fica "em andamento".
            return StatusCode::INTERNAL_SERVER_ERROR;
        }
    };

    let mut stream = request.into_body().into_data_stream();
    let mut received: u64 = 0;
    loop {
        let chunk = match tokio::time::timeout(state.upload_inactivity_timeout, stream.next()).await
        {
            Ok(Some(chunk)) => chunk,
            Ok(None) => break,
            Err(_elapsed) => {
                // Sem nenhum byte por muito tempo: aborta como corpo
                // incompleto e solta o lock do id. fail_guard e o guarda do
                // lease cuidam do resto ao sair de escopo.
                return StatusCode::BAD_REQUEST;
            }
        };
        let bytes = match chunk {
            Ok(bytes) => bytes,
            Err(_) => {
                // Conexão interrompida no meio: fail_guard marca o Item como
                // failed e apaga o .part ao sair de escopo.
                return StatusCode::BAD_REQUEST;
            }
        };
        if expected_size.is_some_and(|declarado| received + bytes.len() as u64 > declarado) {
            return StatusCode::BAD_REQUEST;
        }
        if let Err(e) = file.write_all(&bytes).await {
            eprintln!("falha ao gravar {temp_path:?}: {e}");
            return StatusCode::INTERNAL_SERVER_ERROR;
        }
        received += bytes.len() as u64;
        state.items.file_progress(item.id, received);
        if fixed_length && received == size {
            // Content-Length delimita o corpo HTTP. X-File-Size é só uma
            // declaração: chunked precisa chegar ao terminador para concluir.
            break;
        }
    }
    if expected_size.is_some_and(|declarado| received != declarado) {
        return StatusCode::BAD_REQUEST;
    }

    // Flush, rename e aviso de fim rodam numa tarefa própria a partir daqui,
    // sem nenhum await entre o fim do corpo e o spawn: se a conexão cair
    // bem neste instante (o celular não precisa ficar esperando a resposta),
    // a future desta requisição pode ser cancelada, mas uma tarefa spawnada
    // não é abortada por isso — sem essa separação flush/rename/file_done
    // já podiam ter acontecido pela metade e o aviso de fim nunca sair.
    let items = state.items.clone();
    let received_files = state.received_files.clone();
    let accepted_item_ids = state.accepted_item_ids.clone();
    let accepted_file_log = state.accepted_file_log.clone();
    let final_path_bg = final_path.clone();
    let temp_path_bg = temp_path.clone();
    let concluido = tokio::spawn(async move {
        // Move a reserva do nome e o guarda de falha para dentro da tarefa:
        // eles só saem de cena depois do rename, nunca enquanto ele ainda
        // está em voo.
        let _item_id_lock = item_id_lock;
        let _lease = _lease;
        let _fail_guard = fail_guard;
        // Rebind depois do guarda pela mesma razão de lá em cima: se o flush
        // falhar e sairmos sem chegar ao `drop(file)`, o handle ainda fecha
        // antes de o guarda apagar o `.part`.
        let mut file = file;

        // tokio::fs::File bufferiza a escrita; write_all().await devolve
        // antes de os bytes necessariamente chegarem ao disco, e o `drop`
        // seguinte não é ponto de sincronização — sem o flush, o rename
        // podia disparar sem o último pedaço gravado.
        if let Err(e) = file.flush().await {
            eprintln!("falha ao sincronizar {temp_path_bg:?}: {e}");
            return false; // _fail_guard limpa ao sair de escopo
        }
        drop(file);

        match tokio::fs::rename(&temp_path_bg, &final_path_bg).await {
            Ok(()) => {
                #[cfg(test)]
                pause_once(&state.after_file_rename).await;
                // Só agora o arquivo existe com o nome final: o sinal de fim
                // não pode vir antes disso, ou a UI tentaria ler um caminho
                // que ainda não existe.
                {
                    let mut files = received_files.lock().expect("arquivos recebidos sem panic");
                    files.insert(item.id, final_path_bg.clone());
                    if items.delete_requested(item.id) {
                        if let Some(completed) = items.snapshot().iter().find(|i| i.id == item.id) {
                            recycle_received_file(&files, completed);
                        }
                        files.remove(&item.id);
                    }
                    items.file_done(item.id);
                }
                if let Some(id) = item_id {
                    // Depois do rename: um crash entre os dois duplica, nunca
                    // dá 201 a um reenvio cujo arquivo não chegou à Pasta.
                    if let Some(log) = &accepted_file_log {
                        log.lock().expect("ids de arquivo sem panic").record(&id);
                    }
                    accepted_item_ids
                        .lock()
                        .expect("ids aceitos sem panic")
                        .insert(id);
                }
                true
            }
            Err(e) => {
                eprintln!("falha ao renomear {temp_path_bg:?} para {final_path_bg:?}: {e}");
                false // _fail_guard limpa ao sair de escopo
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
/// função, é um dos dois sinais de presença (o outro é um download em
/// andamento, `get_outbox_file`, CLEAN-015).
async fn get_listen(State(state): State<AppState>, headers: HeaderMap) -> impl IntoResponse {
    let mut token_changes = state.token.0.subscribe();
    if !authorized(&headers, &state.token) {
        return StatusCode::UNAUTHORIZED.into_response();
    }
    let _presenca = state.presence.enter();
    let (pendentes, mut novos) = state.outbox.subscribe();
    let itens: Vec<OutItem> = if !pendentes.is_empty() {
        pendentes
    } else {
        let next = novos.recv();
        let revoked = token_changes.changed();
        futures_util::pin_mut!(next, revoked);
        match futures_util::future::select(next, revoked).await {
            futures_util::future::Either::Left((Some(item), _)) => vec![item],
            _ => return StatusCode::UNAUTHORIZED.into_response(),
        }
    };

    if !authorized(&headers, &state.token) {
        return StatusCode::UNAUTHORIZED.into_response();
    }

    let corpo = itens
        .iter()
        .map(|item| serde_json::to_string(item).expect("OutItem serializa"))
        .collect::<Vec<_>>()
        .join("\n");
    (StatusCode::OK, corpo).into_response()
}

async fn head_listen(State(state): State<AppState>, headers: HeaderMap) -> StatusCode {
    if !authorized(&headers, &state.token) {
        return StatusCode::UNAUTHORIZED;
    }
    if state.presence.get() {
        StatusCode::OK
    } else {
        StatusCode::NO_CONTENT
    }
}

/// Bytes de um Item de arquivo ainda Pendente na fila de saída. O guarda de
/// presença vai dentro do stream do corpo, não no handler: o hyper escreve o
/// corpo depois de o handler retornar, então um guarda preso ao handler
/// soltaria a presença antes de o primeiro byte sair (CLEAN-015). Em pedaços
/// de 64 KiB para o guarda sobreviver até o stream acabar ou o cliente
/// desconectar — um pedaço só (o arquivo inteiro de uma vez) faria o stream
/// terminar, e o guarda cair, na primeira leitura.
async fn get_outbox_file(
    State(state): State<AppState>,
    method: axum::http::Method,
    headers: HeaderMap,
    AxPath(id): AxPath<u64>,
) -> impl IntoResponse {
    if !authorized(&headers, &state.token) {
        return StatusCode::UNAUTHORIZED.into_response();
    }
    match state.outbox.file_bytes(id) {
        Ok(bytes) => {
            const CHUNK: usize = 64 * 1024;
            let tamanho = bytes.len();
            // Content-Length explícito: sem ele o hyper não sabe o tamanho de
            // um corpo em stream e usa Transfer-Encoding: chunked, que muda o
            // formato dos bytes na rede. Content-Type é o que a tupla
            // `(StatusCode, Vec<u8>)` mandava antes.
            let resposta = axum::response::Response::builder()
                .status(StatusCode::OK)
                .header(axum::http::header::CONTENT_LENGTH, tamanho)
                .header(axum::http::header::CONTENT_TYPE, "application/octet-stream");
            // O axum manda HEAD para este handler, mas sem corpo não há
            // download: entrar e sair da presença piscaria a janela.
            if method == axum::http::Method::HEAD {
                return resposta
                    .body(axum::body::Body::empty())
                    .expect("resposta HEAD com Content-Length é válida");
            }
            let guard = state.presence.enter();
            // `Bytes::slice` fatia sem copiar: o arquivo fica uma vez só na
            // memória, como antes do stream.
            let bytes = axum::body::Bytes::from(bytes);
            let stream =
                futures_util::stream::iter((0..tamanho).step_by(CHUNK)).map(move |inicio| {
                    let _presenca = &guard;
                    Ok::<_, io::Error>(bytes.slice(inicio..(inicio + CHUNK).min(tamanho)))
                });
            resposta
                .body(axum::body::Body::from_stream(stream))
                .expect("resposta com Content-Length e corpo em stream é válida")
        }
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

/// Ao sair de escopo, marca o Item como `failed` e apaga o `.part` — a não
/// ser que o envio já tenha chegado a um estado terminal. As duas chamadas
/// são idempotentes por conta própria (`file_failed` só age sobre um Item
/// ainda `Receiving`; apagar um arquivo que já foi renomeado para o nome
/// final simplesmente não encontra nada), então isso cobre numa tacada só:
/// erro no meio do corpo, escrita ou rename que falha, e a própria future de
/// `post_file` sendo cancelada pela conexão caindo — o único caso em que
/// nenhuma outra linha deste arquivo roda, só o `Drop`.
struct FailGuard {
    items: Items,
    item_id: u64,
    temp_path: PathBuf,
}

impl Drop for FailGuard {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.temp_path);
        self.items.file_failed(self.item_id);
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

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;

    fn file_request(id: &str, name: &str, body: &'static [u8]) -> Request {
        Request::builder()
            .method("POST")
            .uri("/items/file")
            .header("authorization", "Bearer segredo-de-teste")
            .header("x-item-id", id)
            .header("x-file-name", name)
            .header("content-length", body.len())
            .body(Body::from(body))
            .unwrap()
    }

    fn test_pause() -> TestPause {
        TestPause {
            reached: Arc::new(tokio::sync::Notify::new()),
            resume: Arc::new(tokio::sync::Notify::new()),
        }
    }

    #[test]
    fn retry_espera_id_ser_registrado_apos_cancelar_handler() {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        runtime.block_on(async {
            let root = tempfile::tempdir().unwrap();
            let pasta = root.path().join("pasta");
            let finalization = test_pause();
            let state = AppState {
                token: TOKEN_FOR_TEST.to_string().into(),
                items: Items::new(),
                outbox: Outbox::new(root.path().join("outbox")).unwrap(),
                presence: Presence::default(),
                pasta: Arc::new(Mutex::new(Some(pasta.clone()))),
                leases: Arc::new(Mutex::new(HashSet::new())),
                received_files: Arc::new(Mutex::new(HashMap::new())),
                accepted_item_ids: Arc::new(Mutex::new(HashSet::new())),
                accepted_file_log: None,
                file_item_id_lock: Arc::new(tokio::sync::Mutex::new(())),
                upload_inactivity_timeout: UPLOAD_INACTIVITY_TIMEOUT,
                before_file_item_id_lock: Arc::new(Mutex::new(None)),
                after_file_rename: Arc::new(Mutex::new(Some(finalization.clone()))),
            };

            let first = tokio::spawn(post_file(
                State(state.clone()),
                file_request("mesmo-id", "arquivo.bin", b"primeiro"),
            ));
            finalization.reached.notified().await;
            first.abort();
            assert!(first.await.unwrap_err().is_cancelled());

            let before_retry_lock = test_pause();
            *state
                .before_file_item_id_lock
                .lock()
                .expect("pausa de teste sem panic") = Some(before_retry_lock.clone());
            let retry = tokio::spawn(post_file(
                State(state.clone()),
                file_request("mesmo-id", "arquivo.bin", b"retry"),
            ));
            before_retry_lock.reached.notified().await;

            assert!(
                state.file_item_id_lock.try_lock().is_err(),
                "a finalização deve manter o id reservado após cancelar o handler"
            );
            before_retry_lock.resume.notify_one();
            finalization.resume.notify_one();

            assert_eq!(retry.await.unwrap(), StatusCode::CREATED);
            assert_eq!(state.items.snapshot().len(), 1);
            assert_eq!(std::fs::read_dir(pasta).unwrap().count(), 1);
        });
    }

    const TOKEN_FOR_TEST: &str = "segredo-de-teste";
}
