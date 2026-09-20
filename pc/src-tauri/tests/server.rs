//! Costura do PC: o protocolo HTTP. Os testes agem como o celular.

use pc_lib::items::Items;
use pc_lib::server;
use std::io::Write;
use std::net::TcpStream;
use std::time::Duration;

const TOKEN: &str = "segredo-de-teste";

/// Sobe o servidor em porta aleatória com token conhecido e uma Pasta
/// temporária, que morre com o teste.
fn sobe() -> (String, Items, tempfile::TempDir) {
    let pasta = tempfile::tempdir().expect("tempdir");
    let items = Items::new();
    let running = server::spawn(
        "127.0.0.1:0".parse().unwrap(),
        TOKEN.to_string(),
        items.clone(),
        Some(pasta.path().to_path_buf()),
    )
    .expect("servidor sobe em porta aleatória");
    (format!("http://{}", running.addr), items, pasta)
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
    let (base, items, _pasta) = sobe();

    let status = post_texto(&base, Some(TOKEN), "olá");

    assert_eq!(status, 201);
    assert_eq!(textos(&items), vec!["olá"]);
}

#[test]
fn post_sem_token_e_recusado_e_nada_entra() {
    let (base, items, _pasta) = sobe();

    let status = post_texto(&base, None, "olá");

    assert_eq!(status, 401);
    assert!(textos(&items).is_empty());
}

#[test]
fn post_com_token_errado_e_recusado_e_nada_entra() {
    let (base, items, _pasta) = sobe();

    let status = post_texto(&base, Some("outro-token"), "olá");

    assert_eq!(status, 401);
    assert!(textos(&items).is_empty());
}

#[test]
fn dois_posts_preservam_ordem_de_chegada() {
    let (base, items, _pasta) = sobe();

    post_texto(&base, Some(TOKEN), "primeiro");
    post_texto(&base, Some(TOKEN), "segundo");

    assert_eq!(textos(&items), vec!["primeiro", "segundo"]);
}

#[test]
fn post_arquivo_grava_na_pasta_com_conteudo_identico() {
    let (base, _items, pasta) = sobe();
    let bytes = b"conteudo do arquivo de teste";

    let status = post_arquivo(&base, Some(TOKEN), "foto.jpg", bytes);

    assert_eq!(status, 201);
    let gravado = std::fs::read(pasta.path().join("foto.jpg")).expect("arquivo gravado na Pasta");
    assert_eq!(gravado, bytes);
}

#[test]
fn segundo_post_do_mesmo_nome_ganha_sufixo() {
    let (base, _items, pasta) = sobe();

    post_arquivo(&base, Some(TOKEN), "foto.jpg", b"primeiro");
    post_arquivo(&base, Some(TOKEN), "foto.jpg", b"segundo");

    assert_eq!(
        std::fs::read(pasta.path().join("foto.jpg")).expect("primeiro arquivo"),
        b"primeiro"
    );
    assert_eq!(
        std::fs::read(pasta.path().join("foto (1).jpg")).expect("segundo arquivo, com sufixo"),
        b"segundo"
    );
}

#[test]
fn nome_com_caminho_fica_dentro_da_pasta() {
    let (base, _items, pasta) = sobe();

    let status = post_arquivo(&base, Some(TOKEN), "../x.txt", b"conteudo");

    assert_eq!(status, 201);
    assert_eq!(
        std::fs::read(pasta.path().join("x.txt")).expect("arquivo dentro da Pasta"),
        b"conteudo"
    );
    assert!(
        !pasta
            .path()
            .parent()
            .expect("Pasta tem diretório pai (é um tempdir)")
            .join("x.txt")
            .exists(),
        "\"../x.txt\" não pode escapar da Pasta"
    );
}

#[test]
fn post_sem_x_file_size_usa_content_length_e_completa() {
    let (base, items, _pasta) = sobe();
    let bytes = b"conteudo sem cabecalho de tamanho, so Content-Length";

    let status = post_arquivo_sem_x_file_size(&base, "sem-tamanho.bin", bytes);

    assert_eq!(status, 201);
    let arquivos = arquivos(&items);
    assert_eq!(arquivos.len(), 1);
    let (nome, size, received, done) = &arquivos[0];
    assert_eq!(nome, "sem-tamanho.bin");
    assert_eq!(*size, bytes.len() as u64, "size vem do Content-Length");
    assert_eq!(*received, bytes.len() as u64);
    assert!(*done, "sem X-File-Size o arquivo ainda deve terminar (done)");
}

#[test]
fn post_arquivo_vazio_completa_com_arquivo_de_0_byte() {
    let (base, items, pasta) = sobe();

    let status = post_arquivo(&base, Some(TOKEN), "vazio.bin", b"");

    assert_eq!(status, 201);
    let gravado =
        std::fs::read(pasta.path().join("vazio.bin")).expect("arquivo de 0 byte gravado");
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
    let (base, _items, pasta) = sobe();
    let addr = base.strip_prefix("http://").expect("base é http://<addr>");

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

    std::thread::sleep(Duration::from_millis(300));

    let gravado_1 = std::fs::read(pasta.path().join("foto.jpg")).expect("primeiro arquivo");
    let gravado_2 = std::fs::read(pasta.path().join("foto (1).jpg")).expect("segundo arquivo, com sufixo");

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
    let (base, _items, pasta) = sobe();
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
        !pasta.path().join("grande.bin").exists(),
        "envio incompleto não pode deixar arquivo com o nome final"
    );
}
