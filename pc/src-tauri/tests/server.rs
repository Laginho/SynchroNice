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
