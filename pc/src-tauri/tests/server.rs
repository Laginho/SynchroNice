//! Costura do PC: o protocolo HTTP. Os testes agem como o celular.

use pc_lib::items::{FileStatus, ItemBody, Items};
use pc_lib::outbox::{OutBody, OutItem, Outbox};
use pc_lib::par::Par;
use pc_lib::server;
use std::io::{Read, Write};
use std::net::{Shutdown, TcpStream};
use std::time::{Duration, Instant};

const TOKEN: &str = "segredo-de-teste";

#[test]
fn nome_utf8_percent_encoded_preserva_acentos_espaco_e_percentual() {
    let (base, _items, _outbox, raiz) = sobe();
    let response = agent()
        .post(format!("{base}/items/file"))
        .header("Authorization", format!("Bearer {TOKEN}"))
        .header("X-File-Name-Utf8", "relat%C3%B3rio%20%2B%20100%25.pdf")
        .send(b"pdf")
        .unwrap();
    assert_eq!(response.status().as_u16(), 201);
    assert_eq!(
        std::fs::read(raiz.path().join("pasta/relatório + 100%.pdf")).unwrap(),
        b"pdf"
    );
}

#[test]
fn rotacionar_descarta_pendentes_e_revoga_escuta_aberta() {
    let raiz = tempfile::tempdir().unwrap();
    let par = Par::load_or_create(raiz.path()).unwrap();
    let antigo = par.token();
    let outbox = Outbox::new(raiz.path().join("outbox")).unwrap();
    let running = server::spawn(
        "127.0.0.1:0".parse().unwrap(),
        par.clone(),
        Items::new(),
        outbox.clone(),
        None,
    )
    .unwrap();
    let base = format!("http://{}", running.addr);
    let (tx, rx) = std::sync::mpsc::channel();
    running.presence.on_change(move |online| {
        if online {
            let _ = tx.send(());
        }
    });
    let listen_base = base.clone();
    let (done_tx, done_rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        done_tx
            .send(requisicao_crua(
                &listen_base,
                "GET",
                "/listen",
                Some(&antigo),
            ))
            .unwrap();
    });
    rx.recv_timeout(Duration::from_secs(5)).unwrap();
    par.rotate(&outbox).unwrap();
    let revoked = done_rx.recv_timeout(Duration::from_secs(2));
    // Também libera a thread no código antigo, para o teste não deixá-la presa.
    outbox.enqueue_text("segredo novo".into()).unwrap();
    assert_eq!(
        revoked.expect("rotação deve encerrar a escuta antiga").0,
        401
    );

    let old_file = outbox
        .enqueue_file("antigo.bin".into(), b"segredo antigo")
        .unwrap();
    par.rotate(&outbox).unwrap();
    assert!(outbox.pending().is_empty());
    assert!(Outbox::new(raiz.path().join("outbox"))
        .unwrap()
        .pending()
        .is_empty());
    assert_eq!(
        requisicao_crua(
            &base,
            "GET",
            &format!("/outbox/{}/file", old_file.id),
            Some(&par.token())
        )
        .0,
        404
    );
    outbox.enqueue_text("so o par atual".into()).unwrap();
    let (_, body) = requisicao_crua(&base, "GET", "/listen", Some(&par.token()));
    assert!(String::from_utf8(body).unwrap().contains("so o par atual"));
}

#[test]
fn head_listen_confirma_presenca_sem_consumir_o_long_poll() {
    let raiz = tempfile::tempdir().unwrap();
    let par = Par::load_or_create(raiz.path()).unwrap();
    let token = par.token();
    let outbox = Outbox::new(raiz.path().join("outbox")).unwrap();
    let running = server::spawn(
        "127.0.0.1:0".parse().unwrap(),
        par,
        Items::new(),
        outbox.clone(),
        None,
    )
    .unwrap();
    let base = format!("http://{}", running.addr);

    assert_eq!(
        requisicao_crua_com_timeout(
            &base,
            "HEAD",
            "/listen",
            Some(&token),
            Some(Duration::from_secs(2)),
        )
        .0,
        204
    );
    assert_eq!(
        requisicao_crua_com_timeout(
            &base,
            "HEAD",
            "/listen",
            Some("token-errado"),
            Some(Duration::from_secs(2)),
        )
        .0,
        401
    );

    let (online_tx, online_rx) = std::sync::mpsc::channel();
    running.presence.on_change(move |online| {
        if online {
            let _ = online_tx.send(());
        }
    });
    let listen_base = base.clone();
    let listen_token = token.clone();
    let (done_tx, done_rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        done_tx
            .send(requisicao_crua(
                &listen_base,
                "GET",
                "/listen",
                Some(&listen_token),
            ))
            .unwrap();
    });

    online_rx.recv_timeout(Duration::from_secs(5)).unwrap();
    assert_eq!(
        requisicao_crua_com_timeout(
            &base,
            "HEAD",
            "/listen",
            Some(&token),
            Some(Duration::from_secs(2)),
        )
        .0,
        200
    );
    assert_eq!(
        requisicao_crua_com_timeout(
            &base,
            "HEAD",
            "/listen",
            Some("token-errado"),
            Some(Duration::from_secs(2)),
        )
        .0,
        401
    );
    outbox
        .enqueue_text("entrega continua no GET".into())
        .unwrap();

    let (status, body) = done_rx.recv_timeout(Duration::from_secs(5)).unwrap();
    assert_eq!(status, 200);
    assert!(String::from_utf8(body)
        .unwrap()
        .contains("entrega continua no GET"));
}

#[test]
fn download_em_andamento_conta_como_presenca() {
    let raiz = tempfile::tempdir().unwrap();
    let par = Par::load_or_create(raiz.path()).unwrap();
    let token = par.token();
    let outbox = Outbox::new(raiz.path().join("outbox")).unwrap();
    let running = server::spawn(
        "127.0.0.1:0".parse().unwrap(),
        par,
        Items::new(),
        outbox.clone(),
        None,
    )
    .unwrap();
    let base = format!("http://{}", running.addr);

    // Grande o bastante para não caber nos buffers de socket — sem isso o GET
    // termina antes de o teste conseguir segurar a conexão em andamento.
    let bytes = vec![b'X'; 8 * 1024 * 1024];
    let item = outbox
        .enqueue_file("grande.bin".to_string(), &bytes)
        .expect("enfileira arquivo");

    let (online_tx, online_rx) = std::sync::mpsc::channel();
    running.presence.on_change(move |online| {
        let _ = online_tx.send(online);
    });

    let (status, stream) =
        conecta_e_le_status_sem_ler_corpo(&base, &format!("/outbox/{}/file", item.id), &token);
    assert_eq!(status, 200);

    assert!(
        online_rx.recv_timeout(Duration::from_secs(5)).unwrap(),
        "critério 1: download em andamento, sem /listen aberto, entra na presença"
    );
    assert_eq!(
        requisicao_crua_com_timeout(
            &base,
            "HEAD",
            "/listen",
            Some(&token),
            Some(Duration::from_secs(2)),
        )
        .0,
        200,
        "critério 1: HEAD /listen confirma a presença do download"
    );

    // Solta o socket sem ler o corpo — desconecta no meio.
    drop(stream);
    assert!(
        !online_rx.recv_timeout(Duration::from_secs(5)).unwrap(),
        "critério 2 (desconecta no meio): presença cai quando o cliente some"
    );
    assert_eq!(
        requisicao_crua_com_timeout(
            &base,
            "HEAD",
            "/listen",
            Some(&token),
            Some(Duration::from_secs(2)),
        )
        .0,
        204,
        "critério 2: HEAD /listen volta a 204"
    );

    // Mesmo GET, lido até o fim desta vez: presença cai de novo, e os bytes
    // continuam idênticos ao arquivo enfileirado (critério 4).
    let (status, mut stream) =
        conecta_e_le_status_sem_ler_corpo(&base, &format!("/outbox/{}/file", item.id), &token);
    assert_eq!(status, 200);
    assert!(online_rx.recv_timeout(Duration::from_secs(5)).unwrap());

    let mut corpo = Vec::new();
    stream.read_to_end(&mut corpo).expect("lê corpo até o fim");
    assert_eq!(
        corpo, bytes,
        "critério 4: bytes idênticos ao arquivo enfileirado"
    );
    assert!(
        !online_rx.recv_timeout(Duration::from_secs(5)).unwrap(),
        "critério 2 (termina): presença cai quando o GET termina"
    );
    assert_eq!(
        requisicao_crua_com_timeout(
            &base,
            "HEAD",
            "/listen",
            Some(&token),
            Some(Duration::from_secs(2)),
        )
        .0,
        204
    );

    // Critério 3: recusado (401) ou id inexistente (404) não mexe na presença.
    let (status, _) = requisicao_crua(
        &base,
        "GET",
        &format!("/outbox/{}/file", item.id),
        Some("token-errado"),
    );
    assert_eq!(status, 401);
    let (status, _) = requisicao_crua(&base, "GET", "/outbox/99999/file", Some(&token));
    assert_eq!(status, 404);
    assert!(
        online_rx.try_recv().is_err(),
        "critério 3: 401 e 404 não disparam on_change"
    );

    // O axum responde HEAD com o handler do GET, mas sem corpo não há download em andamento.
    let (status, _) = requisicao_crua(
        &base,
        "HEAD",
        &format!("/outbox/{}/file", item.id),
        Some(&token),
    );
    assert_eq!(status, 200);
    assert!(
        online_rx.recv_timeout(Duration::from_millis(500)).is_err(),
        "HEAD no arquivo não entra nem sai da presença"
    );
}

#[test]
fn reenvio_de_arquivo_depois_de_reiniciar_o_pc_nao_duplica_na_pasta() {
    let raiz = tempfile::tempdir().unwrap();
    let pasta = raiz.path().join("pasta");
    std::fs::create_dir_all(&pasta).unwrap();
    let ids = raiz.path().join("ids-aceitos.txt");
    // Histórico no disco: o próximo upload deve compactá-lo para os 1000 ids mais recentes.
    let historico: String = (0..2000).map(|i| format!("antigo-{i}\n")).collect();
    std::fs::write(&ids, historico).unwrap();
    let sobe = |outbox: &str| {
        let running = server::spawn_with_accepted_ids(
            "127.0.0.1:0".parse().unwrap(),
            TOKEN.to_string(),
            Items::new(),
            Outbox::new(raiz.path().join(outbox)).unwrap(),
            Some(pasta.clone()),
            &ids,
        )
        .unwrap();
        format!("http://{}", running.addr)
    };

    let antes = sobe("outbox-1");
    assert_eq!(
        post_arquivo_com_id(&antes, TOKEN, "arquivo-1", "foto.jpg", b"igual"),
        201
    );
    // O 201 se perdeu e o PC reiniciou: Sessão nova, mas o arquivo continua na Pasta.
    let depois = sobe("outbox-2");
    let persistidos = std::fs::read_to_string(&ids).unwrap();
    assert_eq!(persistidos.lines().count(), 1000);
    assert_eq!(persistidos.lines().next(), Some("antigo-1001"));
    assert_eq!(persistidos.lines().last(), Some("arquivo-1"));
    assert_eq!(
        post_arquivo_com_id(&depois, TOKEN, "arquivo-1", "foto.jpg", b"igual"),
        201
    );

    assert_eq!(std::fs::read_dir(&pasta).unwrap().count(), 1);
    assert_eq!(
        post_arquivo_com_id(&depois, TOKEN, "antigo-1999", "recente.bin", b""),
        201
    );
    assert!(!pasta.join("recente.bin").exists());
    assert_eq!(
        post_arquivo_com_id(&depois, TOKEN, "antigo-0", "expirado.bin", b"novo"),
        201
    );
    assert_eq!(std::fs::read(pasta.join("expirado.bin")).unwrap(), b"novo");
}

#[test]
fn escolher_pasta_com_servidor_no_ar_libera_upload_sem_reiniciar() {
    let raiz = tempfile::tempdir().unwrap();
    let pasta = raiz.path().join("pasta");
    std::fs::create_dir(&pasta).unwrap();
    let running = server::spawn(
        "127.0.0.1:0".parse().unwrap(),
        TOKEN.to_string(),
        Items::new(),
        Outbox::new(raiz.path().join("outbox")).unwrap(),
        None,
    )
    .unwrap();
    let base = format!("http://{}", running.addr);
    // Corpo vazio de propósito: sem Pasta o 503 sai antes de ler o corpo, e no Windows
    // fechar o socket com bytes não lidos pode virar RST (10053) antes de o cliente ler o 503.
    assert_eq!(post_arquivo(&base, Some(TOKEN), "foto.png", b""), 503);
    running.escolher_pasta(raiz.path(), &pasta).unwrap();
    assert_eq!(post_arquivo(&base, Some(TOKEN), "foto.png", b"foto"), 201);
    assert_eq!(std::fs::read(pasta.join("foto.png")).unwrap(), b"foto");
    assert_eq!(pc_lib::config::load_pasta(raiz.path()), Some(pasta));
}

#[test]
fn arquivo_sem_pasta_configurada_e_gravado_em_downloads_synchronice_criada_se_precisar() {
    let config_dir = tempfile::tempdir().unwrap();
    let downloads = tempfile::tempdir().unwrap();
    let pasta = pc_lib::config::pasta_efetiva(config_dir.path(), || {
        Ok::<_, std::io::Error>(downloads.path().to_path_buf())
    })
    .unwrap();
    assert_eq!(pc_lib::config::load_pasta(config_dir.path()), None);
    assert!(!pasta.exists());

    let running = server::spawn(
        "127.0.0.1:0".parse().unwrap(),
        TOKEN.to_string(),
        Items::new(),
        Outbox::new(config_dir.path().join("outbox")).unwrap(),
        Some(pasta.clone()),
    )
    .unwrap();
    let base = format!("http://{}", running.addr);
    let bytes = b"arquivo recebido sem pasta configurada";

    assert_eq!(post_arquivo(&base, Some(TOKEN), "primeiro.bin", bytes), 201);
    assert_eq!(std::fs::read(pasta.join("primeiro.bin")).unwrap(), bytes);
    assert!(
        pasta.is_dir(),
        "o destino padrão é criado no primeiro arquivo"
    );
    assert_eq!(pc_lib::config::load_pasta(config_dir.path()), None);
}

#[test]
fn item_recebido_continua_resolvendo_sua_pasta_depois_da_troca() {
    let raiz = tempfile::tempdir().unwrap();
    let pasta_original = raiz.path().join("original");
    let pasta_nova = raiz.path().join("nova");
    std::fs::create_dir_all(&pasta_original).unwrap();
    std::fs::create_dir_all(&pasta_nova).unwrap();
    let items = Items::new();
    let running = server::spawn(
        "127.0.0.1:0".parse().unwrap(),
        TOKEN.to_string(),
        items.clone(),
        Outbox::new(raiz.path().join("outbox")).unwrap(),
        Some(pasta_original.clone()),
    )
    .unwrap();
    let base = format!("http://{}", running.addr);

    assert_eq!(
        post_arquivo(&base, Some(TOKEN), "foto.png", b"original"),
        201
    );
    let id_original = items.snapshot()[0].id;

    running.escolher_pasta(raiz.path(), &pasta_nova).unwrap();

    assert_eq!(post_arquivo(&base, Some(TOKEN), "foto.png", b"novo"), 201);
    let id_novo = items.snapshot()[1].id;

    let caminho_original = running.caminho_do_arquivo(id_original).unwrap();
    let caminho_novo = running.caminho_do_arquivo(id_novo).unwrap();
    assert_eq!(caminho_original, pasta_original.join("foto.png"));
    assert_eq!(caminho_novo, pasta_nova.join("foto.png"));
    assert_eq!(std::fs::read(caminho_original).unwrap(), b"original");
    assert_eq!(std::fs::read(caminho_novo).unwrap(), b"novo");
}

/// Sobe o servidor em porta aleatória com token conhecido, uma Pasta e uma
/// fila de saída temporárias, que morrem com o teste.
fn sobe() -> (String, Items, Outbox, tempfile::TempDir) {
    let raiz = tempfile::tempdir().expect("tempdir");
    let pasta = raiz.path().join("pasta");
    std::fs::create_dir_all(&pasta).expect("cria Pasta");
    let items = Items::new();
    let outbox = Outbox::new(raiz.path().join("outbox")).expect("cria fila de saída");
    let running = server::spawn(
        "127.0.0.1:0".parse().unwrap(),
        TOKEN.to_string(),
        items.clone(),
        outbox.clone(),
        Some(pasta.clone()),
    )
    .expect("servidor sobe em porta aleatória");
    (format!("http://{}", running.addr), items, outbox, raiz)
}

/// Como `sobe()`, mas com o timeout de inatividade do upload encurtado, para
/// os testes de travamento não esperarem os 30 s de verdade.
fn sobe_com_timeout_de_upload(timeout: Duration) -> (String, Items, Outbox, tempfile::TempDir) {
    let raiz = tempfile::tempdir().expect("tempdir");
    let pasta = raiz.path().join("pasta");
    std::fs::create_dir_all(&pasta).expect("cria Pasta");
    let items = Items::new();
    let outbox = Outbox::new(raiz.path().join("outbox")).expect("cria fila de saída");
    let running = server::spawn_with_upload_timeout(
        "127.0.0.1:0".parse().unwrap(),
        TOKEN.to_string(),
        items.clone(),
        outbox.clone(),
        Some(pasta.clone()),
        timeout,
    )
    .expect("servidor sobe em porta aleatória");
    (format!("http://{}", running.addr), items, outbox, raiz)
}

/// Como `sobe()`, mas autentica com o token vivo de `par` em vez de um
/// `TOKEN` fixo — rotacionar `par` depois já vale para o servidor, sem
/// reiniciá-lo (SYN-005).
fn sobe_com_par(par: Par) -> (String, tempfile::TempDir) {
    let raiz = tempfile::tempdir().expect("tempdir");
    let pasta = raiz.path().join("pasta");
    std::fs::create_dir_all(&pasta).expect("cria Pasta");
    let items = Items::new();
    let outbox = Outbox::new(raiz.path().join("outbox")).expect("cria fila de saída");
    let running = server::spawn(
        "127.0.0.1:0".parse().unwrap(),
        par,
        items,
        outbox,
        Some(pasta),
    )
    .expect("servidor sobe em porta aleatória");
    (format!("http://{}", running.addr), raiz)
}

fn agent() -> ureq::Agent {
    ureq::Agent::new_with_config(
        ureq::Agent::config_builder()
            .http_status_as_error(false)
            .build(),
    )
}

/// POST /items/text com o corpo `texto`; devolve o status HTTP.
fn post_texto(base: &str, token: Option<&str>, texto: &str) -> u16 {
    let mut req = agent().post(format!("{base}/items/text"));
    if let Some(t) = token {
        req = req.header("Authorization", format!("Bearer {t}"));
    }
    req.send(texto)
        .expect("conexão com o servidor")
        .status()
        .as_u16()
}

fn post_texto_com_id(base: &str, token: &str, id: &str, texto: &str) -> u16 {
    agent()
        .post(format!("{base}/items/text"))
        .header("Authorization", format!("Bearer {token}"))
        .header("X-Item-Id", id)
        .send(texto)
        .expect("conexão com o servidor")
        .status()
        .as_u16()
}

/// POST /items/file com `bytes` no corpo e o nome dado; devolve o status HTTP.
fn post_arquivo(base: &str, token: Option<&str>, nome: &str, bytes: &[u8]) -> u16 {
    let mut req = agent()
        .post(format!("{base}/items/file"))
        .header("X-File-Name", nome)
        .header("X-File-Size", bytes.len().to_string());
    if let Some(t) = token {
        req = req.header("Authorization", format!("Bearer {t}"));
    }
    req.send(bytes)
        .expect("conexão com o servidor")
        .status()
        .as_u16()
}

fn post_arquivo_com_id(base: &str, token: &str, id: &str, nome: &str, bytes: &[u8]) -> u16 {
    agent()
        .post(format!("{base}/items/file"))
        .header("Authorization", format!("Bearer {token}"))
        .header("X-Item-Id", id)
        .header("X-File-Name", nome)
        .header("X-File-Size", bytes.len().to_string())
        .send(bytes)
        .expect("conexão com o servidor")
        .status()
        .as_u16()
}

/// Como `post_arquivo`, mas sem `X-File-Size`: o tamanho só viaja no
/// `Content-Length` que o cliente HTTP calcula sozinho, como um `curl`
/// comum faria.
fn post_arquivo_sem_x_file_size(base: &str, nome: &str, bytes: &[u8]) -> u16 {
    agent()
        .post(format!("{base}/items/file"))
        .header("Authorization", format!("Bearer {TOKEN}"))
        .header("X-File-Name", nome)
        .send(bytes)
        .expect("conexão com o servidor")
        .status()
        .as_u16()
}

/// Requisição HTTP crua via socket: manda `metodo path` com o token (se
/// houver), sem corpo, e lê a resposta até a conexão fechar (`Connection:
/// close`, honrado pelo servidor). Evita depender da leitura de corpo em
/// stream do cliente HTTP de teste, que este arquivo não usa em nenhum outro
/// lugar — cobre tanto o corpo simples de `/listen` quanto os bytes crus de
/// `/outbox/{id}/file`.
fn requisicao_crua(base: &str, metodo: &str, path: &str, token: Option<&str>) -> (u16, Vec<u8>) {
    requisicao_crua_com_timeout(base, metodo, path, token, None)
}

fn requisicao_crua_com_timeout(
    base: &str,
    metodo: &str,
    path: &str,
    token: Option<&str>,
    timeout: Option<Duration>,
) -> (u16, Vec<u8>) {
    let addr = base.strip_prefix("http://").expect("base é http://<addr>");
    let mut stream = TcpStream::connect(addr).expect("conecta no servidor");
    if timeout.is_some() {
        stream
            .set_read_timeout(timeout)
            .expect("configura timeout da resposta");
    }
    let auth = token
        .map(|t| format!("Authorization: Bearer {t}\r\n"))
        .unwrap_or_default();
    let pedido = format!(
        "{metodo} {path} HTTP/1.1\r\nHost: {addr}\r\n{auth}Content-Length: 0\r\nConnection: close\r\n\r\n"
    );
    stream.write_all(pedido.as_bytes()).expect("escreve pedido");

    let mut bruto = Vec::new();
    stream.read_to_end(&mut bruto).expect("lê resposta");

    let fim_cabecalho = bruto
        .windows(4)
        .position(|w| w == b"\r\n\r\n")
        .expect("resposta tem cabeçalho");
    let status = String::from_utf8_lossy(&bruto[..fim_cabecalho])
        .lines()
        .next()
        .and_then(|l| l.split_whitespace().nth(1))
        .and_then(|c| c.parse().ok())
        .expect("linha de status");
    (status, bruto[fim_cabecalho + 4..].to_vec())
}

/// Como `requisicao_crua`, mas para quando o teste precisa segurar a conexão
/// em andamento: lê só até o fim do cabeçalho, byte a byte, e devolve o
/// `TcpStream` aberto sem tocar no corpo — quem chama decide se lê o resto,
/// solta o socket (desconecta no meio) ou nem um nem outro.
fn conecta_e_le_status_sem_ler_corpo(base: &str, path: &str, token: &str) -> (u16, TcpStream) {
    let addr = base.strip_prefix("http://").expect("base é http://<addr>");
    let mut stream = TcpStream::connect(addr).expect("conecta no servidor");
    let pedido = format!(
        "GET {path} HTTP/1.1\r\nHost: {addr}\r\nAuthorization: Bearer {token}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    );
    stream.write_all(pedido.as_bytes()).expect("escreve pedido");

    let mut cabecalho = Vec::new();
    let mut byte = [0u8; 1];
    loop {
        stream.read_exact(&mut byte).expect("lê cabeçalho");
        cabecalho.push(byte[0]);
        if cabecalho.ends_with(b"\r\n\r\n") {
            break;
        }
    }
    let status = String::from_utf8_lossy(&cabecalho)
        .lines()
        .next()
        .and_then(|l| l.split_whitespace().nth(1))
        .and_then(|c| c.parse().ok())
        .expect("linha de status");
    (status, stream)
}

fn textos(items: &Items) -> Vec<String> {
    items
        .snapshot()
        .into_iter()
        .filter_map(|i| match i.body {
            pc_lib::items::ItemBody::Text { text } => Some(text),
            _ => None,
        })
        .collect()
}

/// `(nome, size, received, status)` de cada Item de arquivo, em ordem de chegada.
fn arquivos(items: &Items) -> Vec<(String, u64, u64, FileStatus)> {
    items
        .snapshot()
        .into_iter()
        .filter_map(|i| match i.body {
            pc_lib::items::ItemBody::File {
                name,
                size,
                received,
                status,
            } => Some((name, size, received, status)),
            _ => None,
        })
        .collect()
}

#[test]
fn post_com_token_cria_item_de_texto() {
    let (base, items, _outbox, _raiz) = sobe();

    let status = post_texto(&base, Some(TOKEN), "olá");

    assert_eq!(status, 201);
    assert_eq!(textos(&items), vec!["olá"]);
}

#[test]
fn reenvio_de_texto_com_o_mesmo_id_nao_duplica_o_item() {
    let (base, items, _outbox, _raiz) = sobe();

    assert_eq!(post_texto_com_id(&base, TOKEN, "texto-1", "igual"), 201);
    assert_eq!(post_texto_com_id(&base, TOKEN, "texto-1", "igual"), 201);
    assert_eq!(post_texto_com_id(&base, TOKEN, "texto-2", "igual"), 201);

    assert_eq!(textos(&items), vec!["igual", "igual"]);
}

#[test]
fn post_sem_token_e_recusado_e_nada_entra() {
    let (base, items, _outbox, _raiz) = sobe();

    let status = post_texto(&base, None, "olá");

    assert_eq!(status, 401);
    assert!(textos(&items).is_empty());
}

#[test]
fn post_com_token_errado_e_recusado_e_nada_entra() {
    let (base, items, _outbox, _raiz) = sobe();

    let status = post_texto(&base, Some("outro-token"), "olá");

    assert_eq!(status, 401);
    assert!(textos(&items).is_empty());
}

#[test]
fn dois_posts_preservam_ordem_de_chegada() {
    let (base, items, _outbox, _raiz) = sobe();

    post_texto(&base, Some(TOKEN), "primeiro");
    post_texto(&base, Some(TOKEN), "segundo");

    assert_eq!(textos(&items), vec!["primeiro", "segundo"]);
}

#[test]
fn post_arquivo_grava_na_pasta_com_conteudo_identico() {
    let (base, _items, _outbox, raiz) = sobe();
    let bytes = b"conteudo do arquivo de teste";

    let status = post_arquivo(&base, Some(TOKEN), "foto.jpg", bytes);

    assert_eq!(status, 201);
    let gravado = std::fs::read(raiz.path().join("pasta").join("foto.jpg"))
        .expect("arquivo gravado na Pasta");
    assert_eq!(gravado, bytes);
}

#[test]
fn reenvio_de_arquivo_com_o_mesmo_id_nao_duplica_item_nem_arquivo() {
    let (base, items, _outbox, raiz) = sobe();

    assert_eq!(
        post_arquivo_com_id(&base, TOKEN, "arquivo-1", "foto.jpg", b"igual"),
        201
    );
    assert_eq!(
        post_arquivo_com_id(&base, TOKEN, "arquivo-1", "foto.jpg", b"igual"),
        201
    );
    assert_eq!(
        post_arquivo_com_id(&base, TOKEN, "arquivo-2", "foto.jpg", b"igual"),
        201
    );

    assert_eq!(arquivos(&items).len(), 2);
    assert_eq!(
        std::fs::read_dir(raiz.path().join("pasta"))
            .unwrap()
            .count(),
        2
    );
}

#[test]
fn envios_concorrentes_do_mesmo_id_criam_um_item_e_um_arquivo() {
    let (base, items, _outbox, raiz) = sobe();
    let addr = base.strip_prefix("http://").expect("base é http://<addr>");
    let body = vec![b'A'; 4096];
    let request = format!(
        "POST /items/file HTTP/1.1\r\n\
         Host: {addr}\r\n\
         Authorization: Bearer {TOKEN}\r\n\
         X-Item-Id: mesmo-id\r\n\
         X-File-Name: simultaneo.bin\r\n\
         Content-Length: {}\r\n\
         Connection: close\r\n\
         \r\n",
        body.len()
    );
    let mut first = TcpStream::connect(addr).expect("conecta primeiro envio");
    let mut second = TcpStream::connect(addr).expect("conecta segundo envio");
    first
        .set_read_timeout(Some(Duration::from_secs(5)))
        .unwrap();
    second
        .set_read_timeout(Some(Duration::from_secs(5)))
        .unwrap();

    first.write_all(request.as_bytes()).unwrap();
    second.write_all(request.as_bytes()).unwrap();
    std::thread::sleep(Duration::from_millis(100));
    first.write_all(&body).unwrap();
    second.write_all(&body).unwrap();

    let mut first_response = String::new();
    let mut second_response = String::new();
    first.read_to_string(&mut first_response).unwrap();
    second.read_to_string(&mut second_response).unwrap();

    assert!(first_response.starts_with("HTTP/1.1 201"));
    assert!(second_response.starts_with("HTTP/1.1 201"));
    assert_eq!(arquivos(&items).len(), 1);
    assert_eq!(
        std::fs::read_dir(raiz.path().join("pasta"))
            .unwrap()
            .count(),
        1
    );
}

#[test]
fn retry_apos_cancelar_resposta_na_finalizacao_nao_duplica_arquivo() {
    let (base, items, _outbox, raiz) = sobe();
    let addr = base.strip_prefix("http://").expect("base é http://<addr>");
    let body = vec![b'A'; 32 * 1024 * 1024];
    let connection = std::sync::Arc::new(std::sync::Mutex::new(None::<TcpStream>));
    let connection_on_progress = connection.clone();
    let (cancelled_tx, cancelled_rx) = std::sync::mpsc::channel();
    let expected_size = body.len() as u64;
    items.on_new(move |item| {
        if matches!(
            &item.body,
            ItemBody::File { name, received, status, .. }
                if name == "cancelado.bin"
                    && *received == expected_size
                    && *status == FileStatus::Receiving
        ) {
            if let Some(connection) = connection_on_progress.lock().unwrap().take() {
                connection.shutdown(Shutdown::Both).unwrap();
                cancelled_tx.send(()).unwrap();
            }
        }
    });
    let request = format!(
        "POST /items/file HTTP/1.1\r\n\
         Host: {addr}\r\n\
         Authorization: Bearer {TOKEN}\r\n\
         X-Item-Id: resposta-cancelada\r\n\
         X-File-Name: cancelado.bin\r\n\
         Content-Length: {}\r\n\
         Connection: close\r\n\
         \r\n",
        body.len()
    );
    let mut first = TcpStream::connect(addr).expect("conecta primeiro envio");
    *connection.lock().unwrap() = Some(first.try_clone().unwrap());
    first.write_all(request.as_bytes()).unwrap();
    first.write_all(&body).unwrap();
    cancelled_rx
        .recv_timeout(Duration::from_secs(5))
        .expect("servidor deve consumir o primeiro envio");
    drop(first);

    assert_eq!(
        post_arquivo_com_id(
            &base,
            TOKEN,
            "resposta-cancelada",
            "cancelado.bin",
            b"retry"
        ),
        201
    );
    assert_eq!(arquivos(&items).len(), 1);
    assert_eq!(
        std::fs::read_dir(raiz.path().join("pasta"))
            .unwrap()
            .count(),
        1
    );
}

#[test]
fn segundo_post_do_mesmo_nome_ganha_sufixo() {
    let (base, _items, _outbox, raiz) = sobe();
    let pasta = raiz.path().join("pasta");

    post_arquivo(&base, Some(TOKEN), "foto.jpg", b"primeiro");
    post_arquivo(&base, Some(TOKEN), "foto.jpg", b"segundo");

    assert_eq!(
        std::fs::read(pasta.join("foto.jpg")).expect("primeiro arquivo"),
        b"primeiro"
    );
    assert_eq!(
        std::fs::read(pasta.join("foto (1).jpg")).expect("segundo arquivo, com sufixo"),
        b"segundo"
    );
}

#[test]
fn nome_com_caminho_fica_dentro_da_pasta() {
    let (base, _items, _outbox, raiz) = sobe();
    let pasta = raiz.path().join("pasta");

    let status = post_arquivo(&base, Some(TOKEN), "../x.txt", b"conteudo");

    assert_eq!(status, 201);
    assert_eq!(
        std::fs::read(pasta.join("x.txt")).expect("arquivo dentro da Pasta"),
        b"conteudo"
    );
    assert!(
        !raiz.path().join("x.txt").exists(),
        "\"../x.txt\" não pode escapar da Pasta"
    );
}

#[test]
fn post_sem_x_file_size_usa_content_length_e_completa() {
    let (base, items, _outbox, _raiz) = sobe();
    let bytes = b"conteudo sem cabecalho de tamanho, so Content-Length";

    let status = post_arquivo_sem_x_file_size(&base, "sem-tamanho.bin", bytes);

    assert_eq!(status, 201);
    let arquivos = arquivos(&items);
    assert_eq!(arquivos.len(), 1);
    let (nome, size, received, status) = &arquivos[0];
    assert_eq!(nome, "sem-tamanho.bin");
    assert_eq!(*size, bytes.len() as u64, "size vem do Content-Length");
    assert_eq!(*received, bytes.len() as u64);
    assert_eq!(
        *status,
        FileStatus::Done,
        "sem X-File-Size o arquivo ainda deve terminar (done)"
    );
}

#[test]
fn post_arquivo_vazio_completa_com_arquivo_de_0_byte() {
    let (base, items, _outbox, raiz) = sobe();

    let status = post_arquivo(&base, Some(TOKEN), "vazio.bin", b"");

    assert_eq!(status, 201);
    let gravado = std::fs::read(raiz.path().join("pasta").join("vazio.bin"))
        .expect("arquivo de 0 byte gravado");
    assert!(gravado.is_empty());
    let arquivos = arquivos(&items);
    let (_, _, _, status) = arquivos
        .iter()
        .find(|(nome, ..)| nome == "vazio.bin")
        .expect("item do arquivo vazio");
    assert_eq!(
        *status,
        FileStatus::Done,
        "arquivo de 0 byte deve terminar (done)"
    );
}

#[test]
fn dois_envios_concorrentes_do_mesmo_nome_preservam_ambos() {
    let (base, items, _outbox, raiz) = sobe();
    let pasta = raiz.path().join("pasta");
    let addr = base.strip_prefix("http://").expect("base é http://<addr>");

    // Sinal de fim é o evento `file_done` (dispara só depois do rename para
    // o nome final, ver items.rs), não o relógio: sob carga, um envio pode
    // legitimamente terminar depois de um tempo fixo qualquer.
    let (tx, rx) = std::sync::mpsc::channel::<()>();
    items.on_new(move |item| {
        if let ItemBody::File {
            status: FileStatus::Done,
            ..
        } = &item.body
        {
            let _ = tx.send(());
        }
    });

    fn cabecalho(addr: &str, tamanho: usize) -> String {
        format!(
            "POST /items/file HTTP/1.1\r\n\
             Host: {addr}\r\n\
             Authorization: Bearer {TOKEN}\r\n\
             X-File-Name: foto.jpg\r\n\
             Content-Length: {tamanho}\r\n\
             Connection: close\r\n\
             \r\n"
        )
    }

    let corpo_a = vec![b'A'; 4096];
    let corpo_b = vec![b'B'; 4096];

    let mut fluxo_a = TcpStream::connect(addr).expect("conecta A");
    let mut fluxo_b = TcpStream::connect(addr).expect("conecta B");

    // Manda os dois cabeçalhos antes de qualquer byte de corpo: nenhum dos
    // dois envios pode terminar (e portanto renomear) antes que ambos já
    // tenham disparado o handler e reservado um nome — é essa janela que
    // expõe a corrida no nome final.
    fluxo_a
        .write_all(cabecalho(addr, corpo_a.len()).as_bytes())
        .expect("cabeçalhos A");
    fluxo_b
        .write_all(cabecalho(addr, corpo_b.len()).as_bytes())
        .expect("cabeçalhos B");

    // Intercala pedaços pequenos dos dois corpos para manter os dois envios
    // em voo ao mesmo tempo no servidor de thread única.
    for (pedaco_a, pedaco_b) in corpo_a.chunks(256).zip(corpo_b.chunks(256)) {
        fluxo_a.write_all(pedaco_a).expect("pedaço A");
        fluxo_b.write_all(pedaco_b).expect("pedaço B");
        std::thread::sleep(Duration::from_millis(1));
    }
    drop(fluxo_a);
    drop(fluxo_b);

    for _ in 0..2 {
        rx.recv_timeout(Duration::from_secs(5))
            .expect("os dois envios devem terminar (evento file_done) dentro do prazo");
    }

    let gravado_1 = std::fs::read(pasta.join("foto.jpg")).expect("primeiro arquivo");
    let gravado_2 = std::fs::read(pasta.join("foto (1).jpg")).expect("segundo arquivo, com sufixo");

    let mut gravados = vec![gravado_1, gravado_2];
    gravados.sort();
    let mut esperados = vec![corpo_a, corpo_b];
    esperados.sort();
    assert_eq!(
        gravados, esperados,
        "os dois envios concorrentes devem terminar íntegros, sem um sobrescrever o outro"
    );
}

#[test]
fn envio_interrompido_nao_deixa_arquivo_com_nome_final() {
    let (base, _items, _outbox, raiz) = sobe();
    let pasta = raiz.path().join("pasta");
    let addr = base.strip_prefix("http://").expect("base é http://<addr>");

    // Requisição crua: promete 1_000_000 bytes via Content-Length, manda só
    // uma fração e fecha a conexão. Content-Length menor que o prometido é
    // sempre uma requisição incompleta para qualquer servidor HTTP/1.1
    // correto — não depende de nenhum detalhe do axum/hyper.
    let mut stream = TcpStream::connect(addr).expect("conecta no servidor");
    let pedido = format!(
        "POST /items/file HTTP/1.1\r\n\
         Host: {addr}\r\n\
         Authorization: Bearer {TOKEN}\r\n\
         X-File-Name: grande.bin\r\n\
         X-File-Size: 1000000\r\n\
         Content-Length: 1000000\r\n\
         Connection: close\r\n\
         \r\n"
    );
    stream
        .write_all(pedido.as_bytes())
        .expect("escreve cabeçalhos");
    stream
        .write_all(b"so uma fracao dos bytes prometidos")
        .expect("escreve parte do corpo");
    drop(stream); // fecha antes de completar os bytes prometidos

    // Dá tempo do servidor perceber o fechamento antes de checar a Pasta.
    std::thread::sleep(Duration::from_millis(200));

    assert!(
        !pasta.join("grande.bin").exists(),
        "envio incompleto não pode deixar arquivo com o nome final"
    );
}

#[test]
fn envio_chunked_sem_terminador_falha_mesmo_com_tamanho_prometido() {
    let (base, items, _outbox, raiz) = sobe();
    let pasta = raiz.path().join("pasta");
    let addr = base.strip_prefix("http://").expect("base é http://<addr>");

    // O tamanho declarado não substitui o terminador do corpo HTTP.
    let (tx, rx) = std::sync::mpsc::channel::<()>();
    items.on_new(move |item| {
        if let ItemBody::File {
            status: FileStatus::Failed,
            ..
        } = &item.body
        {
            let _ = tx.send(());
        }
    });

    let corpo = vec![b'C'; 4096];
    let pedido = format!(
        "POST /items/file HTTP/1.1\r\n\
         Host: {addr}\r\n\
         Authorization: Bearer {TOKEN}\r\n\
         X-File-Name: chunked.bin\r\n\
         X-File-Size: {}\r\n\
         Transfer-Encoding: chunked\r\n\
         Connection: close\r\n\
         \r\n",
        corpo.len()
    );
    let mut stream = TcpStream::connect(addr).expect("conecta no servidor");
    stream
        .write_all(pedido.as_bytes())
        .expect("escreve cabeçalhos");
    // Chunk de dados completo, mas corpo HTTP incompleto.
    stream
        .write_all(format!("{:x}\r\n", corpo.len()).as_bytes())
        .expect("escreve tamanho do chunk");
    stream.write_all(&corpo).expect("escreve o chunk");
    stream.write_all(b"\r\n").expect("fecha o chunk");
    drop(stream); // cai antes do chunk terminador

    rx.recv_timeout(Duration::from_secs(5))
        .expect("corpo sem terminador deve falhar");

    assert_eq!(std::fs::read_dir(pasta).unwrap().count(), 0);
}

fn chunked_com_tamanho_declarado(size: u64, expected_status: u16) {
    let (base, items, _outbox, raiz) = sobe();
    let addr = base.strip_prefix("http://").unwrap();
    let mut stream = TcpStream::connect(addr).unwrap();
    stream
        .set_read_timeout(Some(Duration::from_secs(5)))
        .unwrap();
    write!(stream, "POST /items/file HTTP/1.1\r\nHost: {addr}\r\nAuthorization: Bearer {TOKEN}\r\nX-File-Name: inteiro.bin\r\nX-File-Size: {size}\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n3\r\nabc\r\n3\r\ndef\r\n0\r\n\r\n").unwrap();
    let mut response = String::new();
    stream.read_to_string(&mut response).unwrap();
    assert!(
        response.starts_with(&format!("HTTP/1.1 {expected_status}")),
        "{response}"
    );
    let pasta = raiz.path().join("pasta");
    if expected_status == 201 {
        assert_eq!(std::fs::read(pasta.join("inteiro.bin")).unwrap(), b"abcdef");
    } else {
        assert_eq!(arquivos(&items)[0].3, FileStatus::Failed);
        assert_eq!(std::fs::read_dir(pasta).unwrap().count(), 0);
    }
}

#[test]
fn chunked_maior_que_tamanho_declarado_falha() {
    chunked_com_tamanho_declarado(3, 400);
}

#[test]
fn chunked_menor_que_tamanho_declarado_falha() {
    chunked_com_tamanho_declarado(9, 400);
}

#[test]
fn chunked_com_tamanho_correto_termina() {
    chunked_com_tamanho_declarado(6, 201);
}

#[test]
fn chunked_com_tamanho_zero_e_bytes_falha() {
    chunked_com_tamanho_declarado(0, 400);
}

#[test]
fn envio_cortado_no_meio_nao_deixa_part_na_pasta() {
    let (base, items, _outbox, raiz) = sobe();
    let pasta = raiz.path().join("pasta");
    let addr = base.strip_prefix("http://").expect("base é http://<addr>");

    let (tx, rx) = std::sync::mpsc::channel::<()>();
    items.on_new(move |item| {
        if let ItemBody::File {
            status: FileStatus::Failed,
            ..
        } = &item.body
        {
            let _ = tx.send(());
        }
    });

    let mut stream = TcpStream::connect(addr).expect("conecta no servidor");
    let pedido = "POST /items/file HTTP/1.1\r\n\
         Host: HOST\r\n\
         Authorization: Bearer TOKEN\r\n\
         X-File-Name: cortado.bin\r\n\
         X-File-Size: 1000000\r\n\
         Content-Length: 1000000\r\n\
         Connection: close\r\n\
         \r\n"
        .replace("HOST", addr)
        .replace("TOKEN", TOKEN);
    stream
        .write_all(pedido.as_bytes())
        .expect("escreve cabeçalhos");
    stream
        .write_all(b"so uma fracao dos bytes prometidos")
        .expect("escreve parte do corpo");
    drop(stream); // fecha antes de completar os bytes prometidos

    rx.recv_timeout(Duration::from_secs(5))
        .expect("o envio cortado deve marcar o Item como failed");

    let sobras: Vec<_> = std::fs::read_dir(&pasta)
        .expect("lê a Pasta")
        .filter_map(|e| e.ok())
        .filter(|e| e.file_name().to_string_lossy().contains(".part-"))
        .collect();
    assert!(
        sobras.is_empty(),
        "envio cortado não pode deixar .part na Pasta: {sobras:?}"
    );
}

#[test]
fn envio_cortado_no_meio_marca_item_como_failed() {
    let (base, items, _outbox, _raiz) = sobe();
    let addr = base.strip_prefix("http://").expect("base é http://<addr>");

    let (tx, rx) = std::sync::mpsc::channel::<()>();
    items.on_new(move |item| {
        if let ItemBody::File {
            status: FileStatus::Failed,
            ..
        } = &item.body
        {
            let _ = tx.send(());
        }
    });

    let mut stream = TcpStream::connect(addr).expect("conecta no servidor");
    let pedido = "POST /items/file HTTP/1.1\r\n\
         Host: HOST\r\n\
         Authorization: Bearer TOKEN\r\n\
         X-File-Name: cortado2.bin\r\n\
         X-File-Size: 1000000\r\n\
         Content-Length: 1000000\r\n\
         Connection: close\r\n\
         \r\n"
        .replace("HOST", addr)
        .replace("TOKEN", TOKEN);
    stream
        .write_all(pedido.as_bytes())
        .expect("escreve cabeçalhos");
    stream
        .write_all(b"so uma fracao dos bytes prometidos")
        .expect("escreve parte do corpo");
    drop(stream); // fecha antes de completar os bytes prometidos

    rx.recv_timeout(Duration::from_secs(5))
        .expect("o envio cortado deve marcar o Item como failed");

    let status = items
        .snapshot()
        .into_iter()
        .find_map(|i| match i.body {
            ItemBody::File { name, status, .. } if name == "cortado2.bin" => Some(status),
            _ => None,
        })
        .expect("item do envio cortado");
    assert_eq!(status, FileStatus::Failed);
}

#[test]
fn envio_cortado_nao_consume_o_id_e_pode_ser_repetido() {
    let (base, items, _outbox, raiz) = sobe();
    let addr = base.strip_prefix("http://").expect("base é http://<addr>");
    let (tx, rx) = std::sync::mpsc::channel::<()>();
    items.on_new(move |item| {
        if matches!(
            &item.body,
            ItemBody::File {
                status: FileStatus::Failed,
                ..
            }
        ) {
            let _ = tx.send(());
        }
    });

    let mut stream = TcpStream::connect(addr).expect("conecta no servidor");
    let pedido = "POST /items/file HTTP/1.1\r\n\
         Host: HOST\r\n\
         Authorization: Bearer TOKEN\r\n\
         X-Item-Id: arquivo-cortado\r\n\
         X-File-Name: cortado.bin\r\n\
         Content-Length: 1000000\r\n\
         Connection: close\r\n\
         \r\n"
        .replace("HOST", addr)
        .replace("TOKEN", TOKEN);
    stream.write_all(pedido.as_bytes()).unwrap();
    stream.write_all(b"incompleto").unwrap();
    drop(stream);
    rx.recv_timeout(Duration::from_secs(5))
        .expect("o envio cortado deve terminar antes do retry");

    assert_eq!(
        post_arquivo_com_id(&base, TOKEN, "arquivo-cortado", "cortado.bin", b"completo"),
        201
    );
    assert_eq!(
        std::fs::read(raiz.path().join("pasta/cortado.bin")).unwrap(),
        b"completo"
    );
}

#[test]
fn upload_preso_libera_o_lock_e_o_id_pode_ser_reenviado() {
    let (base, items, _outbox, raiz) = sobe_com_timeout_de_upload(Duration::from_millis(200));
    let pasta = raiz.path().join("pasta");
    let addr = base.strip_prefix("http://").expect("base é http://<addr>");

    let corpo = vec![b'A'; 4096];
    let pedido = format!(
        "POST /items/file HTTP/1.1\r\n\
         Host: {addr}\r\n\
         Authorization: Bearer {TOKEN}\r\n\
         X-Item-Id: preso\r\n\
         X-File-Name: preso.bin\r\n\
         Content-Length: {}\r\n\
         Connection: close\r\n\
         \r\n",
        corpo.len()
    );
    let mut travado = TcpStream::connect(addr).expect("conecta upload que vai travar");
    travado
        .write_all(pedido.as_bytes())
        .expect("escreve cabeçalhos");
    travado
        .write_all(&corpo[..10])
        .expect("escreve só um pedaço");
    // Conexão fica aberta sem mais bytes: só o timeout de inatividade pode
    // abortá-la, não um fechamento do lado do cliente.
    std::thread::sleep(Duration::from_millis(50));

    // Numa thread com prazo: sem o timeout do servidor o segundo upload
    // espera o lock para sempre, e o teste tem que falhar, não travar o gate.
    let segundo_base = base.clone();
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let _ = tx.send(post_arquivo_com_id(
            &segundo_base,
            TOKEN,
            "outro-id",
            "segundo.bin",
            b"conteudo do segundo",
        ));
    });
    assert_eq!(
        rx.recv_timeout(Duration::from_secs(5))
            .expect("segundo upload não pode esperar o lock para sempre"),
        201,
        "segundo upload deve passar assim que o primeiro estourar o timeout, sem reiniciar"
    );
    assert!(
        std::fs::read_dir(&pasta).unwrap().all(|e| !e
            .unwrap()
            .file_name()
            .to_string_lossy()
            .contains(".part-")),
        "upload travado não pode deixar .part na Pasta"
    );
    // O Item que nasceu no início do upload termina como falhou, igual a um
    // corte de conexão — nunca como concluído.
    let status = items
        .snapshot()
        .into_iter()
        .find_map(|i| match i.body {
            ItemBody::File { name, status, .. } if name == "preso.bin" => Some(status),
            _ => None,
        })
        .expect("item do upload travado");
    assert_eq!(status, FileStatus::Failed);

    assert_eq!(
        post_arquivo_com_id(&base, TOKEN, "preso", "preso.bin", b"reenviado"),
        201,
        "o id do upload abortado não pode ter ficado marcado como aceito"
    );
    assert_eq!(
        std::fs::read(pasta.join("preso.bin")).unwrap(),
        b"reenviado"
    );

    drop(travado);
}

#[test]
fn upload_lento_mas_continuo_nao_e_abortado_pelo_timeout() {
    let (base, _items, _outbox, raiz) = sobe_com_timeout_de_upload(Duration::from_millis(150));
    let pasta = raiz.path().join("pasta");
    let addr = base.strip_prefix("http://").expect("base é http://<addr>");

    let pedaco = vec![b'B'; 16];
    let total = pedaco.len() * 6;
    let pedido = format!(
        "POST /items/file HTTP/1.1\r\n\
         Host: {addr}\r\n\
         Authorization: Bearer {TOKEN}\r\n\
         X-File-Name: lento.bin\r\n\
         Content-Length: {total}\r\n\
         Connection: close\r\n\
         \r\n"
    );
    let mut stream = TcpStream::connect(addr).expect("conecta no servidor");
    stream
        .set_read_timeout(Some(Duration::from_secs(5)))
        .unwrap();
    stream
        .write_all(pedido.as_bytes())
        .expect("escreve cabeçalhos");
    for _ in 0..6 {
        // Cada intervalo fica abaixo do timeout de 150ms; a soma (~480ms) o
        // ultrapassa de sobra — só o intervalo isolado pode importar.
        std::thread::sleep(Duration::from_millis(80));
        stream.write_all(&pedaco).expect("escreve pedaço");
    }

    let mut response = String::new();
    stream.read_to_string(&mut response).unwrap();
    assert!(response.starts_with("HTTP/1.1 201"), "{response}");
    assert_eq!(std::fs::read(pasta.join("lento.bin")).unwrap().len(), total);
}

// SYN-004: direção PC -> celular. O PC é o remetente e gera o id do Item
// (spec, "Protocolo de Itens"); só a confirmação do celular remove o Item da
// fila de Pendentes.

#[test]
fn texto_enfileirado_sem_cliente_fica_pendente() {
    let (_base, _items, outbox, _raiz) = sobe();

    outbox
        .enqueue_text("olá celular".to_string())
        .expect("enfileira texto");

    let pendentes = outbox.pending();
    assert_eq!(pendentes.len(), 1);
    assert_eq!(
        pendentes[0].body,
        OutBody::Text {
            text: "olá celular".to_string()
        }
    );
}

#[test]
fn escuta_entrega_pendentes_em_ordem_e_confirmacao_os_remove_da_fila() {
    let (base, _items, outbox, _raiz) = sobe();
    outbox
        .enqueue_text("primeiro".to_string())
        .expect("enfileira texto");
    outbox
        .enqueue_text("segundo".to_string())
        .expect("enfileira texto");

    let (status, corpo) = requisicao_crua(&base, "GET", "/listen", Some(TOKEN));
    assert_eq!(status, 200);

    let entregues: Vec<OutItem> = String::from_utf8(corpo)
        .expect("corpo é UTF-8")
        .lines()
        .map(|linha| serde_json::from_str(linha).expect("linha é um Item"))
        .collect();
    let textos: Vec<String> = entregues
        .iter()
        .map(|item| match &item.body {
            OutBody::Text { text } => text.clone(),
            _ => panic!("esperava texto"),
        })
        .collect();
    assert_eq!(
        textos,
        vec!["primeiro", "segundo"],
        "a escuta entrega os Pendentes em ordem de chegada"
    );

    for item in &entregues {
        let (status, _) = requisicao_crua(
            &base,
            "POST",
            &format!("/outbox/{}/ack", item.id),
            Some(TOKEN),
        );
        assert_eq!(status, 204);
    }

    assert!(
        outbox.pending().is_empty(),
        "confirmados saem da fila de Pendentes — ficam Entregues"
    );
}

#[test]
fn arquivo_enfileirado_e_baixado_pela_rota_devolve_bytes_identicos() {
    let (base, _items, outbox, _raiz) = sobe();
    let bytes = b"bytes do arquivo para o celular baixar";

    let item = outbox
        .enqueue_file("foto.jpg".to_string(), bytes)
        .expect("enfileira arquivo");

    let (status, corpo) = requisicao_crua(
        &base,
        "GET",
        &format!("/outbox/{}/file", item.id),
        Some(TOKEN),
    );

    assert_eq!(status, 200);
    assert_eq!(corpo, bytes);
}

#[test]
fn arquivo_de_saida_ilegivel_no_disco_vira_500_nao_404() {
    let (base, _items, outbox, raiz) = sobe();

    let item = outbox
        .enqueue_file("foto.jpg".to_string(), b"bytes originais")
        .expect("enfileira arquivo");
    let arquivo = raiz.path().join("outbox").join(format!("{}.bin", item.id));
    std::fs::remove_file(&arquivo).expect("remove arquivo para simular erro de leitura");
    std::fs::create_dir(&arquivo).expect("substitui por diretório: leitura falha sem ser NotFound");

    let (status, _) = requisicao_crua(
        &base,
        "GET",
        &format!("/outbox/{}/file", item.id),
        Some(TOKEN),
    );

    assert_eq!(
        status, 500,
        "erro de leitura que não é \"arquivo sumiu\" não pode virar 404 (o celular lê 404 como Item já resolvido)"
    );
}

#[test]
fn fila_de_saida_sobrevive_a_recriar_o_servidor_do_mesmo_diretorio() {
    let raiz = tempfile::tempdir().expect("tempdir");
    let dir = raiz.path().join("outbox");

    let original = Outbox::new(dir.clone()).expect("cria fila de saída");
    original
        .enqueue_text("antes de reiniciar".to_string())
        .expect("enfileira texto");
    original
        .enqueue_file("arquivo.bin".to_string(), b"conteudo persistido")
        .expect("enfileira arquivo");

    let recriada = Outbox::new(dir).expect("recria fila de saída do mesmo diretório");

    assert_eq!(
        recriada.pending(),
        original.pending(),
        "a fila recriada do mesmo diretório vê os mesmos Pendentes, na mesma ordem"
    );
    let item_arquivo = recriada
        .pending()
        .into_iter()
        .find(|i| matches!(i.body, OutBody::File { .. }))
        .expect("item de arquivo entre os recarregados");
    assert_eq!(
        recriada.file_bytes(item_arquivo.id).ok(),
        Some(b"conteudo persistido".to_vec()),
        "bytes do arquivo sobrevivem à recriação, não só os metadados"
    );
}

// SYN-005: o Par (nome de instância e token do QR), gerado uma vez e
// gravado na configuração; "parear de novo" rotaciona o token em voo.

#[test]
fn criar_par_gera_token_e_nome_de_instancia_e_os_grava() {
    let dir = tempfile::tempdir().expect("tempdir");

    let par = Par::load_or_create(dir.path()).expect("cria o Par");

    assert!(
        par.token().len() >= 16,
        "token curto demais: {:?}",
        par.token()
    );
    assert!(
        !par.nome_instancia().is_empty(),
        "nome de instância não pode ser vazio"
    );
}

#[test]
fn recriar_o_par_do_mesmo_diretorio_devolve_o_mesmo() {
    let dir = tempfile::tempdir().expect("tempdir");

    let primeiro = Par::load_or_create(dir.path()).expect("cria o Par");
    let segundo = Par::load_or_create(dir.path()).expect("recria o Par");

    assert_eq!(primeiro.token(), segundo.token());
    assert_eq!(primeiro.nome_instancia(), segundo.nome_instancia());
}

#[test]
fn rotacionar_troca_o_token_e_o_servidor_recusa_o_antigo_e_aceita_o_novo() {
    let dir = tempfile::tempdir().expect("tempdir");
    let par = Par::load_or_create(dir.path()).expect("cria o Par");
    let antigo = par.token();
    let (base, _raiz) = sobe_com_par(par.clone());

    assert_eq!(post_texto(&base, Some(&antigo), "antes de rotacionar"), 201);

    let novo = par
        .rotate(&Outbox::new(_raiz.path().join("outbox")).unwrap())
        .expect("rotaciona o token");

    assert_ne!(antigo, novo, "rotacionar deve trocar o token");
    assert_eq!(
        post_texto(&base, Some(&antigo), "depois de rotacionar"),
        401,
        "o token antigo deve ser recusado imediatamente, sem reiniciar o servidor"
    );
    assert_eq!(post_texto(&base, Some(&novo), "com o token novo"), 201);
}

// SYN-019: Apagar tudo, na mesma costura HTTP dos envios.
#[test]
fn observador_de_upload_concluido_pode_consultar_o_arquivo_recebido() {
    let (base, items, _outbox, running, _raiz) = sobe_para_exclusao();
    let running = std::sync::Arc::new(running);
    let (done_tx, done_rx) = std::sync::mpsc::channel();
    items.on_new(move |item| {
        if matches!(
            item.body,
            ItemBody::File {
                status: FileStatus::Done,
                ..
            }
        ) {
            let path = running.caminho_do_arquivo(item.id).unwrap();
            let _ = done_tx.send(std::fs::read(path).unwrap());
        }
    });
    let (http_tx, http_rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let status = post_arquivo_com_id(&base, TOKEN, "foto", "foto.png", b"imagem");
        let _ = http_tx.send(status);
    });

    assert_eq!(
        done_rx
            .recv_timeout(Duration::from_secs(3))
            .expect("Done permite resolver e ler o arquivo sem deadlock"),
        b"imagem"
    );
    assert_eq!(http_rx.recv_timeout(Duration::from_secs(3)).unwrap(), 201);
}

fn post_exclusao(base: &str, token: Option<&str>, body: &str) -> u16 {
    let bounded = ureq::Agent::new_with_config(
        ureq::Agent::config_builder()
            .http_status_as_error(false)
            .timeout_global(Some(Duration::from_secs(10)))
            .build(),
    );
    let mut req = bounded
        .post(format!("{base}/items/delete"))
        .header("Content-Type", "application/json");
    if let Some(token) = token {
        req = req.header("Authorization", format!("Bearer {token}"));
    }
    req.send(body).unwrap().status().as_u16()
}

#[test]
fn exclusao_de_texto_por_id_do_remetente_e_idempotente_sem_reusar_ids_locais() {
    let (base, items, outbox, _raiz) = sobe();
    assert_eq!(post_texto_com_id(&base, TOKEN, "primeiro", "apagar"), 201);
    assert_eq!(post_texto_com_id(&base, TOKEN, "segundo", "conservar"), 201);
    let id_antigo = items.snapshot()[1].id;

    let desconhecida = r#"{"do_pc":[999],"do_celular":["desconhecido"]}"#;
    assert_eq!(post_exclusao(&base, Some(TOKEN), desconhecida), 204);
    assert_eq!(textos(&items), vec!["apagar", "conservar"]);

    let exclusao = r#"{"do_pc":[],"do_celular":["primeiro","primeiro"]}"#;
    assert_eq!(post_exclusao(&base, Some(TOKEN), exclusao), 204);
    assert_eq!(post_exclusao(&base, Some(TOKEN), exclusao), 204);
    assert_eq!(textos(&items), vec!["conservar"]);
    assert!(
        outbox.pending().is_empty(),
        "não devolve a exclusão ao celular"
    );

    assert_eq!(post_texto_com_id(&base, TOKEN, "terceiro", "novo"), 201);
    assert!(items.snapshot()[1].id > id_antigo);
    assert_eq!(post_texto_com_id(&base, TOKEN, "primeiro", "retry"), 201);
    assert_eq!(
        textos(&items),
        vec!["conservar", "novo"],
        "retry não ressuscita o Item"
    );
}

#[test]
fn exclusao_exige_bearer_e_corpo_com_os_dois_espacos_de_ids() {
    let (base, items, _outbox, _raiz) = sobe();
    assert_eq!(post_texto_com_id(&base, TOKEN, "texto", "conservar"), 201);
    let body = r#"{"do_pc":[],"do_celular":["texto"]}"#;
    assert_eq!(post_exclusao(&base, None, body), 401);
    assert_eq!(post_exclusao(&base, Some("outro-token"), "inválido"), 401);
    for invalido in [
        "inválido",
        r#"{"do_pc":["1"],"do_celular":[]}"#,
        r#"{"do_pc":[]}"#,
    ] {
        assert_eq!(post_exclusao(&base, Some(TOKEN), invalido), 400);
    }
    assert_eq!(textos(&items), vec!["conservar"]);
}

fn excluir_ids(base: &str, do_pc: &[u64], do_celular: &[&str]) -> u16 {
    let body = serde_json::json!({ "do_pc": do_pc, "do_celular": do_celular });
    post_exclusao(base, Some(TOKEN), &body.to_string())
}

fn sobe_para_exclusao() -> (String, Items, Outbox, server::Running, tempfile::TempDir) {
    let raiz = tempfile::tempdir().unwrap();
    let items = Items::new();
    let outbox = Outbox::new(raiz.path().join("outbox")).unwrap();
    let running = server::spawn(
        "127.0.0.1:0".parse().unwrap(),
        TOKEN.to_string(),
        items.clone(),
        outbox.clone(),
        Some(raiz.path().join("pasta")),
    )
    .unwrap();
    (
        format!("http://{}", running.addr),
        items,
        outbox,
        running,
        raiz,
    )
}

#[test]
fn exclusao_de_arquivo_esquece_o_caminho_sem_apagar_outros_arquivos() {
    let (base, items, outbox, running, raiz) = sobe_para_exclusao();
    assert_eq!(
        post_arquivo_com_id(&base, TOKEN, "foto", "foto.png", b"foto"),
        201
    );
    assert_eq!(
        post_arquivo_com_id(&base, TOKEN, "outra", "outra.png", b"outra"),
        201
    );
    let removido = items.snapshot()[0].id;

    assert_eq!(excluir_ids(&base, &[], &["foto", "inexistente"]), 204);
    assert!(!raiz.path().join("pasta/foto.png").exists());
    assert_eq!(
        std::fs::read(raiz.path().join("pasta/outra.png")).unwrap(),
        b"outra"
    );
    assert_eq!(items.snapshot().len(), 1);
    assert_eq!(running.caminho_do_arquivo(removido), None);
    assert!(outbox.pending().is_empty());
    assert_eq!(excluir_ids(&base, &[], &["foto"]), 204);
    assert_eq!(
        post_arquivo_com_id(&base, TOKEN, "foto", "foto.png", b"foto"),
        201
    );
    assert!(
        !raiz.path().join("pasta/foto.png").exists(),
        "retry não restaura o arquivo"
    );
}

#[test]
fn exclusao_conserva_arquivo_alterado_e_ignora_arquivo_ja_ausente() {
    let (base, items, _outbox, raiz) = sobe();
    for id in ["alterado", "ausente"] {
        assert_eq!(
            post_arquivo_com_id(&base, TOKEN, id, &format!("{id}.bin"), b"antes"),
            201
        );
    }
    let alterado = raiz.path().join("pasta/alterado.bin");
    std::fs::write(&alterado, b"conteudo alterado pelo usuario").unwrap();
    std::fs::remove_file(raiz.path().join("pasta/ausente.bin")).unwrap();
    assert_eq!(excluir_ids(&base, &[], &["alterado", "ausente"]), 204);
    assert!(items.snapshot().is_empty());
    assert_eq!(
        std::fs::read(alterado).unwrap(),
        b"conteudo alterado pelo usuario"
    );
}

#[test]
fn exclusao_de_item_antigo_nao_apaga_caminho_reutilizado_por_item_novo() {
    let (base, items, _outbox, raiz) = sobe();
    assert_eq!(
        post_arquivo_com_id(&base, TOKEN, "antigo", "foto.png", b"velh"),
        201
    );
    std::fs::remove_file(raiz.path().join("pasta/foto.png")).unwrap();
    assert_eq!(
        post_arquivo_com_id(&base, TOKEN, "novo", "FOTO.PNG", b"novo"),
        201
    );

    assert_eq!(excluir_ids(&base, &[], &["antigo"]), 204);
    assert_eq!(
        std::fs::read(raiz.path().join("pasta/FOTO.PNG")).unwrap(),
        b"novo"
    );
    assert_eq!(items.snapshot().len(), 1);
    assert_eq!(excluir_ids(&base, &[], &["novo"]), 204);
    assert!(!raiz.path().join("pasta/FOTO.PNG").exists());
}

#[test]
fn exclusao_usa_a_pasta_original_e_nao_reconstroi_sessao_anterior() {
    let (base, items, _outbox, running, raiz) = sobe_para_exclusao();
    assert_eq!(
        post_arquivo_com_id(&base, TOKEN, "original", "foto.png", b"antes"),
        201
    );
    let nova = raiz.path().join("nova");
    running.escolher_pasta(raiz.path(), &nova).unwrap();
    assert_eq!(
        post_arquivo_com_id(&base, TOKEN, "nova", "foto.png", b"nova"),
        201
    );
    assert_eq!(excluir_ids(&base, &[], &["original"]), 204);
    assert!(!raiz.path().join("pasta/foto.png").exists());
    assert_eq!(std::fs::read(nova.join("foto.png")).unwrap(), b"nova");
    assert_eq!(items.snapshot().len(), 1);

    let depois = server::spawn(
        "127.0.0.1:0".parse().unwrap(),
        TOKEN.to_string(),
        Items::new(),
        Outbox::new(raiz.path().join("outra-outbox")).unwrap(),
        Some(nova.clone()),
    )
    .unwrap();
    assert_eq!(
        excluir_ids(&format!("http://{}", depois.addr), &[], &["nova"]),
        204
    );
    assert_eq!(
        std::fs::read(nova.join("foto.png")).unwrap(),
        b"nova",
        "uma Sessão nova não recupera o vínculo id → arquivo"
    );
}

#[test]
fn exclusao_de_enviados_remove_pendentes_e_entregues_sem_tocar_no_original() {
    let (base, _items, outbox, raiz) = sobe();
    let original = raiz.path().join("original.bin");
    std::fs::write(&original, b"original").unwrap();
    let arquivo = outbox.enqueue_file_from_path(&original).unwrap();
    let entregue = outbox.enqueue_text("entregue".into()).unwrap();
    assert_eq!(
        requisicao_crua(
            &base,
            "POST",
            &format!("/outbox/{}/ack", entregue.id),
            Some(TOKEN)
        )
        .0,
        204
    );
    let fica = outbox.enqueue_text("conservar".into()).unwrap();

    assert_eq!(
        excluir_ids(&base, &[arquivo.id, entregue.id, arquivo.id, 999], &[]),
        204
    );
    assert_eq!(
        outbox
            .session_items()
            .iter()
            .map(|i| i.item.id)
            .collect::<Vec<_>>(),
        vec![fica.id]
    );
    assert_eq!(std::fs::read(original).unwrap(), b"original");
    assert_eq!(
        requisicao_crua(
            &base,
            "GET",
            &format!("/outbox/{}/file", arquivo.id),
            Some(TOKEN)
        )
        .0,
        404
    );
    let (status, body) = requisicao_crua_com_timeout(
        &base,
        "GET",
        "/listen",
        Some(TOKEN),
        Some(Duration::from_secs(3)),
    );
    assert_eq!(status, 200);
    let linhas: Vec<OutItem> = String::from_utf8(body)
        .unwrap()
        .lines()
        .map(|line| serde_json::from_str(line).unwrap())
        .collect();
    assert_eq!(linhas, vec![fica]);
    let recriada = Outbox::new(raiz.path().join("outbox")).unwrap();
    assert_eq!(
        recriada.pending(),
        outbox.pending(),
        "o descarte da fila persiste"
    );
}

fn inicia_upload_parcial(base: &str, items: &Items, id: &str) -> TcpStream {
    let (tx, rx) = std::sync::mpsc::channel();
    items.on_new(move |item| {
        if matches!(
            item.body,
            ItemBody::File {
                received: 4,
                status: FileStatus::Receiving,
                ..
            }
        ) {
            let _ = tx.send(());
        }
    });
    let addr = base.strip_prefix("http://").unwrap();
    let mut stream = TcpStream::connect(addr).unwrap();
    stream
        .set_read_timeout(Some(Duration::from_secs(5)))
        .unwrap();
    write!(stream, "POST /items/file HTTP/1.1\r\nHost: {addr}\r\nAuthorization: Bearer {TOKEN}\r\nX-Item-Id: {id}\r\nX-File-Name: curso.bin\r\nContent-Length: 8\r\nConnection: close\r\n\r\n1234").unwrap();
    rx.recv_timeout(Duration::from_secs(3))
        .expect("o upload recebeu a primeira metade");
    stream
}

#[test]
fn exclusao_durante_upload_espera_o_fim_e_nao_deixa_arquivo_na_pasta() {
    let (base, items, _outbox, raiz) = sobe();
    let mut stream = inicia_upload_parcial(&base, &items, "curso");
    assert_eq!(excluir_ids(&base, &[], &["curso"]), 204);
    stream.write_all(b"5678").unwrap();
    let mut resposta = String::new();
    stream.read_to_string(&mut resposta).unwrap();
    assert!(resposta.starts_with("HTTP/1.1 201"), "{resposta}");
    assert!(items.snapshot().is_empty());
    assert_eq!(
        std::fs::read_dir(raiz.path().join("pasta"))
            .unwrap()
            .count(),
        0
    );
}

#[test]
fn exclusao_durante_upload_que_falha_descarta_item_e_retry() {
    let (base, items, _outbox, raiz) = sobe();
    let stream = inicia_upload_parcial(&base, &items, "cortado");
    assert_eq!(excluir_ids(&base, &[], &["cortado"]), 204);
    drop(stream);
    let limite = Instant::now() + Duration::from_secs(3);
    while !items.snapshot().is_empty() && Instant::now() < limite {
        std::thread::sleep(Duration::from_millis(5));
    }
    assert!(items.snapshot().is_empty());
    assert_eq!(
        std::fs::read_dir(raiz.path().join("pasta"))
            .unwrap()
            .count(),
        0
    );
    assert_eq!(
        post_arquivo_com_id(&base, TOKEN, "cortado", "curso.bin", b"retry"),
        201
    );
    assert!(items.snapshot().is_empty());
    assert!(!raiz.path().join("pasta/curso.bin").exists());
}

#[test]
fn exclusao_de_recebido_reenviado_preserva_o_original_do_enviado() {
    for (local, entregue) in [(false, false), (false, true), (true, false), (true, true)] {
        let (base, items, outbox, running, _raiz) = sobe_para_exclusao();
        assert_eq!(
            post_arquivo_com_id(&base, TOKEN, "foto", "foto.png", b"original"),
            201
        );
        let recebido = items.snapshot()[0].id;
        let original = running.caminho_do_arquivo(recebido).unwrap();
        let enviado = outbox.enqueue_file_from_path(&original).unwrap();
        if entregue {
            assert_eq!(
                requisicao_crua(
                    &base,
                    "POST",
                    &format!("/outbox/{}/ack", enviado.id),
                    Some(TOKEN)
                )
                .0,
                204
            );
        }
        if local {
            running.apagar_tudo(&[recebido], &[enviado.id]).unwrap();
            assert!(outbox.session_items().is_empty());
        } else {
            assert_eq!(excluir_ids(&base, &[], &["foto"]), 204);
            assert_eq!(outbox.session_items().len(), 1);
        }
        assert!(items.snapshot().is_empty());
        assert_eq!(
            std::fs::read(original).unwrap(),
            b"original",
            "a origem de um enviado, Pendente ou Entregue, continua protegida"
        );
    }
}

#[cfg(debug_assertions)]
#[test]
fn exclusao_de_recebido_antigo_durante_rename_preserva_upload_novo() {
    let (base, items, _outbox, running, raiz) = sobe_para_exclusao();
    assert_eq!(
        post_arquivo_com_id(&base, TOKEN, "antigo", "foto.png", b"velh"),
        201
    );
    let antigo = items.snapshot()[0].id;
    let path = raiz.path().join("pasta/foto.png");
    std::fs::remove_file(&path).unwrap();
    let (renamed, resume) = running.pause_after_file_rename_for_tests();
    let upload_base = base.clone();
    let (upload_tx, upload_rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let status = post_arquivo_com_id(&upload_base, TOKEN, "novo", "foto.png", b"novo");
        let _ = upload_tx.send(status);
    });
    renamed
        .recv_timeout(Duration::from_secs(3))
        .expect("o upload novo chegou ao nome final");
    assert_eq!(std::fs::read(&path).unwrap(), b"novo");

    let (delete_tx, delete_rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let _ = delete_tx.send(excluir_ids(&base, &[], &["antigo"]));
    });
    // A exclusão pode esperar a finalização ou terminar antes dela; ambas
    // as ordens devem preservar o arquivo do Item novo fora da seleção.
    let early_delete = delete_rx.recv_timeout(Duration::from_secs(3)).ok();
    resume.send(()).unwrap();
    assert_eq!(
        early_delete.unwrap_or_else(|| delete_rx.recv_timeout(Duration::from_secs(3)).unwrap()),
        204
    );
    assert_eq!(upload_rx.recv_timeout(Duration::from_secs(3)).unwrap(), 201);
    let restantes = items.snapshot();
    assert_eq!(restantes.len(), 1);
    assert_ne!(restantes[0].id, antigo);
    assert_eq!(restantes[0].sender_id.as_deref(), Some("novo"));
    assert_eq!(running.caminho_do_arquivo(restantes[0].id), Some(path.clone()));
    assert_eq!(std::fs::read(path).unwrap(), b"novo");
}

#[test]
fn original_reenviado_continua_protegido_apos_excluir_a_enviada() {
    for (local, entregue) in [(false, false), (false, true), (true, false), (true, true)] {
        let (base, items, outbox, running, _raiz) = sobe_para_exclusao();
        assert_eq!(
            post_arquivo_com_id(&base, TOKEN, "foto", "foto.png", b"original"),
            201
        );
        let recebido = items.snapshot()[0].id;
        let original = running.caminho_do_arquivo(recebido).unwrap();
        let enviado = outbox.enqueue_file_from_path(&original).unwrap();
        if entregue {
            assert_eq!(
                requisicao_crua(
                    &base,
                    "POST",
                    &format!("/outbox/{}/ack", enviado.id),
                    Some(TOKEN)
                )
                .0,
                204
            );
        }

        if local {
            running.apagar_tudo(&[], &[enviado.id]).unwrap();
        } else {
            assert_eq!(excluir_ids(&base, &[enviado.id], &[]), 204);
        }
        assert!(outbox.session_items().is_empty());
        assert_eq!(items.snapshot().len(), 1);
        assert_eq!(std::fs::read(&original).unwrap(), b"original");

        if local {
            running.apagar_tudo(&[recebido], &[]).unwrap();
        } else {
            assert_eq!(excluir_ids(&base, &[], &["foto"]), 204);
        }
        assert!(items.snapshot().is_empty());
        assert_eq!(
            std::fs::read(original).unwrap(),
            b"original",
            "excluir a bolha enviada não encerra a proteção da origem nesta Sessão"
        );
    }
}

#[cfg(windows)]
#[test]
fn exclusao_de_print_preserva_original_reenviado_por_alias_windows() {
    for (local, entregue) in [(false, false), (false, true), (true, false), (true, true)] {
        let (_base, _items, outbox, running, _raiz) = sobe_para_exclusao();
        let base = format!("http://{}", running.addr);
        let print = outbox.enqueue_file("print.png".into(), b"imagem").unwrap();
        let path = outbox.session_file_path(print.id).unwrap();
        let alias = std::path::PathBuf::from(path.to_string_lossy().to_uppercase());
        assert_ne!(path, alias, "o cenário usa grafias diferentes");
        assert_eq!(path.canonicalize().unwrap(), alias.canonicalize().unwrap());
        let reenviado = outbox.enqueue_file_from_path(&alias).unwrap();
        if entregue {
            assert_eq!(
                requisicao_crua(
                    &base,
                    "POST",
                    &format!("/outbox/{}/ack", reenviado.id),
                    Some(TOKEN)
                )
                .0,
                204
            );
        }

        if local {
            running.apagar_tudo(&[], &[print.id]).unwrap();
        } else {
            assert_eq!(excluir_ids(&base, &[print.id], &[]), 204);
        }
        assert_eq!(outbox.session_items().len(), 1);
        assert_eq!(outbox.session_items()[0].item.id, reenviado.id);
        assert_eq!(
            std::fs::read(outbox.session_file_path(reenviado.id).unwrap()).unwrap(),
            b"imagem",
            "a enviada fora da seleção conserva o original, inclusive por alias"
        );
        assert_eq!(std::fs::read(path).unwrap(), b"imagem");
    }
}

fn observa_exclusoes(items: &Items) -> std::sync::mpsc::Receiver<(Vec<u64>, Vec<u64>)> {
    let (tx, rx) = std::sync::mpsc::channel();
    let snapshot = items.clone();
    items.on_delete(move |deleted| {
        // O callback pode consultar a lista; não roda sob o lock de Items.
        let _ = snapshot.snapshot();
        tx.send((deleted.received.clone(), deleted.sent.clone()))
            .unwrap();
    });
    rx
}

#[test]
fn apagar_tudo_local_limpa_so_a_lista_e_controle_nao_vira_bolha() {
    let (base, items, outbox, running, raiz) = sobe_para_exclusao();
    assert_eq!(post_texto_com_id(&base, TOKEN, "texto", "apagar"), 201);
    assert_eq!(
        post_arquivo_com_id(&base, TOKEN, "foto", "foto.png", b"foto"),
        201
    );
    assert_eq!(post_texto_com_id(&base, TOKEN, "fica", "conservar"), 201);
    let ids_recebidos: Vec<u64> = items.snapshot()[..2].iter().map(|item| item.id).collect();
    let recebido_fica = items.snapshot()[2].id;
    let original = raiz.path().join("original.bin");
    std::fs::write(&original, b"original").unwrap();
    let arquivo = outbox.enqueue_file_from_path(&original).unwrap();
    let print = outbox.enqueue_file("print.png".into(), b"print").unwrap();
    let texto = outbox.enqueue_text("entregue".into()).unwrap();
    let print_entregue = outbox.enqueue_file("print.png".into(), b"outro").unwrap();
    let path_print = outbox.session_file_path(print.id).unwrap();
    let path_entregue = outbox.session_file_path(print_entregue.id).unwrap();
    for id in [texto.id, print_entregue.id] {
        assert_eq!(
            requisicao_crua(&base, "POST", &format!("/outbox/{id}/ack"), Some(TOKEN)).0,
            204
        );
    }
    let enviado_fica = outbox.enqueue_text("conservar".into()).unwrap();
    let ids_enviados = vec![arquivo.id, print.id, texto.id, print_entregue.id];
    let exclusoes = observa_exclusoes(&items);
    let (tx, eventos) = std::sync::mpsc::channel();
    outbox.on_change(move |item, entregue| {
        tx.send((item.id, entregue)).unwrap();
    });
    running.apagar_tudo(&ids_recebidos, &ids_enviados).unwrap();
    assert_eq!(
        items
            .snapshot()
            .iter()
            .map(|item| item.id)
            .collect::<Vec<_>>(),
        vec![recebido_fica]
    );
    assert_eq!(
        outbox
            .session_items()
            .iter()
            .map(|sent| sent.item.id)
            .collect::<Vec<_>>(),
        vec![enviado_fica.id]
    );
    assert_eq!(std::fs::read(&original).unwrap(), b"original");
    assert!(!raiz.path().join("pasta/foto.png").exists());
    assert!(!path_print.exists());
    assert!(!path_entregue.exists());
    assert_eq!(
        requisicao_crua(
            &base,
            "GET",
            &format!("/outbox/{}/file", arquivo.id),
            Some(TOKEN)
        )
        .0,
        404
    );
    assert!(eventos.try_recv().is_err(), "controle não dispara enviado");
    assert!(
        exclusoes.try_recv().is_err(),
        "aviso é da exclusão recebida"
    );
    let (status, body) = requisicao_crua_com_timeout(
        &base,
        "GET",
        "/listen",
        Some(TOKEN),
        Some(Duration::from_secs(3)),
    );
    assert_eq!(status, 200);
    let lines: Vec<serde_json::Value> = String::from_utf8(body)
        .unwrap()
        .lines()
        .map(|line| serde_json::from_str(line).unwrap())
        .collect();
    assert_eq!(lines.len(), 2);
    assert_eq!(lines[0]["id"], enviado_fica.id);
    assert_eq!(lines[1]["kind"], "delete");
    assert_eq!(lines[1]["do_pc"], serde_json::json!(ids_enviados));
    assert_eq!(lines[1]["do_celular"], serde_json::json!(["texto", "foto"]));
    assert!(lines[1]["at_ms"].is_u64());
    assert_eq!(lines[1].as_object().unwrap().len(), 5);
    let controle = lines[1]["id"].as_u64().unwrap();
    assert!(controle > enviado_fica.id);
    let recriada = Outbox::new(raiz.path().join("outbox")).unwrap();
    assert_eq!(
        recriada.pending(),
        outbox.pending(),
        "controle persiste para entrega offline"
    );
    assert!(recriada.session_items().is_empty());
    for _ in 0..2 {
        assert_eq!(
            requisicao_crua(
                &base,
                "POST",
                &format!("/outbox/{controle}/ack"),
                Some(TOKEN)
            )
            .0,
            204
        );
    }
    assert!(
        eventos.try_recv().is_err(),
        "ACK de controle também não vira enviado"
    );
    assert_eq!(outbox.pending(), vec![enviado_fica]);
    running.apagar_tudo(&ids_recebidos, &ids_enviados).unwrap();
    assert_eq!(
        outbox.pending().len(),
        1,
        "ids já apagados não geram outro controle"
    );
}

#[test]
fn exclusao_recebida_avisa_uma_vez_com_os_dois_espacos_de_ids() {
    let (base, items, outbox, _raiz) = sobe();
    assert_eq!(post_texto_com_id(&base, TOKEN, "recebido", "apagar"), 201);
    let recebido = items.snapshot()[0].id;
    let enviado = outbox.enqueue_text("apagar".into()).unwrap();
    assert_eq!(recebido, enviado.id, "as sequências podem colidir");
    let notices = observa_exclusoes(&items);
    assert_eq!(
        excluir_ids(&base, &[enviado.id, enviado.id], &["recebido", "recebido"]),
        204
    );
    assert_eq!(
        notices.recv_timeout(Duration::from_secs(3)).unwrap(),
        (vec![recebido], vec![enviado.id])
    );
    assert!(items.snapshot().is_empty());
    assert!(outbox.session_items().is_empty());
    assert_eq!(
        excluir_ids(&base, &[enviado.id, 999], &["recebido", "desconhecido"]),
        204
    );
    assert!(
        notices.try_recv().is_err(),
        "repetidos/desconhecidos não avisam"
    );
}

#[test]
fn aviso_de_exclusao_espera_todos_os_uploads_terminarem_ou_falharem() {
    for interromper in [false, true] {
        let (base, items, outbox, raiz) = sobe();
        assert_eq!(post_texto_com_id(&base, TOKEN, "texto", "apagar"), 201);
        let id_texto = items.snapshot()[0].id;
        let mut stream = inicia_upload_parcial(&base, &items, "curso");
        let id_arquivo = items.snapshot()[1].id;
        let enviado = outbox.enqueue_text("apagar".into()).unwrap();
        let notices = observa_exclusoes(&items);
        let (tx, updates) = std::sync::mpsc::channel();
        items.on_new(move |item| {
            tx.send(item.id).unwrap();
        });
        assert_eq!(excluir_ids(&base, &[enviado.id], &["texto", "curso"]), 204);
        assert!(
            notices.try_recv().is_err(),
            "a exclusão ainda não foi aplicada inteira"
        );
        assert_eq!(excluir_ids(&base, &[enviado.id], &["curso"]), 204);
        if interromper {
            drop(stream);
        } else {
            stream.write_all(b"5678").unwrap();
            let mut response = String::new();
            stream.read_to_string(&mut response).unwrap();
            assert!(response.starts_with("HTTP/1.1 201"), "{response}");
        }
        assert_eq!(
            notices.recv_timeout(Duration::from_secs(3)).unwrap(),
            (vec![id_texto, id_arquivo], vec![enviado.id])
        );
        assert!(notices.try_recv().is_err());
        assert!(
            updates.try_recv().is_err(),
            "upload descartado não recria a bolha"
        );
        assert!(items.snapshot().is_empty());
        assert_eq!(
            std::fs::read_dir(raiz.path().join("pasta"))
                .unwrap()
                .count(),
            0
        );
    }
}

#[test]
fn apagar_tudo_local_durante_upload_enfileira_o_id_e_espera_o_fim() {
    let (base, items, outbox, running, raiz) = sobe_para_exclusao();
    let mut stream = inicia_upload_parcial(&base, &items, "curso");
    let id = items.snapshot()[0].id;
    let notices = observa_exclusoes(&items);
    running.apagar_tudo(&[id], &[]).unwrap();
    let (status, body) = requisicao_crua_com_timeout(
        &base,
        "GET",
        "/listen",
        Some(TOKEN),
        Some(Duration::from_secs(3)),
    );
    assert_eq!(status, 200);
    let control: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(control["kind"], "delete");
    assert_eq!(control["do_pc"], serde_json::json!([]));
    assert_eq!(control["do_celular"], serde_json::json!(["curso"]));
    assert!(outbox.session_items().is_empty());
    stream.write_all(b"5678").unwrap();
    let mut response = String::new();
    stream.read_to_string(&mut response).unwrap();
    assert!(response.starts_with("HTTP/1.1 201"), "{response}");
    assert!(items.snapshot().is_empty());
    assert_eq!(
        std::fs::read_dir(raiz.path().join("pasta"))
            .unwrap()
            .count(),
        0
    );
    assert!(notices.try_recv().is_err());
}

#[test]
fn exclusao_de_arquivo_vazio_e_chunked_de_tamanho_desconhecido() {
    let (base, items, _outbox, raiz) = sobe();
    assert_eq!(
        post_arquivo_com_id(&base, TOKEN, "vazio", "vazio.bin", b""),
        201
    );
    let addr = base.strip_prefix("http://").unwrap();
    let mut stream = TcpStream::connect(addr).unwrap();
    stream
        .set_read_timeout(Some(Duration::from_secs(5)))
        .unwrap();
    write!(stream, "POST /items/file HTTP/1.1\r\nHost: {addr}\r\nAuthorization: Bearer {TOKEN}\r\nX-Item-Id: chunked\r\nX-File-Name: chunked.bin\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n3\r\nabc\r\n0\r\n\r\n").unwrap();
    let mut resposta = String::new();
    stream.read_to_string(&mut resposta).unwrap();
    assert!(resposta.starts_with("HTTP/1.1 201"), "{resposta}");
    assert_eq!(
        std::fs::read(raiz.path().join("pasta/chunked.bin")).unwrap(),
        b"abc"
    );
    assert_eq!(excluir_ids(&base, &[], &["vazio", "chunked"]), 204);
    assert!(items.snapshot().is_empty());
    assert_eq!(
        std::fs::read_dir(raiz.path().join("pasta"))
            .unwrap()
            .count(),
        0
    );
}
