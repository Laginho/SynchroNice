//! Costura do PC: o protocolo HTTP. Os testes agem como o celular.

use pc_lib::items::{ItemBody, Items};
use pc_lib::outbox::{OutBody, OutItem, Outbox};
use pc_lib::par::Par;
use pc_lib::server;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::time::Duration;

const TOKEN: &str = "segredo-de-teste";

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
    let addr = base.strip_prefix("http://").expect("base é http://<addr>");
    let mut stream = TcpStream::connect(addr).expect("conecta no servidor");
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

/// `(nome, size, received, done)` de cada Item de arquivo, em ordem de chegada.
fn arquivos(items: &Items) -> Vec<(String, u64, u64, bool)> {
    items
        .snapshot()
        .into_iter()
        .filter_map(|i| match i.body {
            pc_lib::items::ItemBody::File {
                name,
                size,
                received,
                done,
            } => Some((name, size, received, done)),
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
    let (nome, size, received, done) = &arquivos[0];
    assert_eq!(nome, "sem-tamanho.bin");
    assert_eq!(*size, bytes.len() as u64, "size vem do Content-Length");
    assert_eq!(*received, bytes.len() as u64);
    assert!(
        *done,
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
    let (_, _, _, done) = arquivos
        .iter()
        .find(|(nome, ..)| nome == "vazio.bin")
        .expect("item do arquivo vazio");
    assert!(*done, "arquivo de 0 byte deve terminar (done)");
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
        if let ItemBody::File { done: true, .. } = &item.body {
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
            OutBody::File { .. } => panic!("esperava texto"),
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

    let novo = par.rotate().expect("rotaciona o token");

    assert_ne!(antigo, novo, "rotacionar deve trocar o token");
    assert_eq!(
        post_texto(&base, Some(&antigo), "depois de rotacionar"),
        401,
        "o token antigo deve ser recusado imediatamente, sem reiniciar o servidor"
    );
    assert_eq!(post_texto(&base, Some(&novo), "com o token novo"), 201);
}
