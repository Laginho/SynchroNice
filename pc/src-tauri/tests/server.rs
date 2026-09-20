//! Costura do PC: o protocolo HTTP. Os testes agem como o celular.

use pc_lib::items::Items;
use pc_lib::server;

const TOKEN: &str = "segredo-de-teste";

/// Sobe o servidor em porta aleatória com token conhecido.
fn sobe() -> (String, Items) {
    let items = Items::new();
    let running = server::spawn(
        "127.0.0.1:0".parse().unwrap(),
        TOKEN.to_string(),
        items.clone(),
    )
    .expect("servidor sobe em porta aleatória");
    (format!("http://{}", running.addr), items)
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

fn textos(items: &Items) -> Vec<String> {
    items.snapshot().into_iter().map(|i| i.text).collect()
}

#[test]
fn post_com_token_cria_item_de_texto() {
    let (base, items) = sobe();

    let status = post_texto(&base, Some(TOKEN), "olá");

    assert_eq!(status, 201);
    assert_eq!(textos(&items), vec!["olá"]);
}

#[test]
fn post_sem_token_e_recusado_e_nada_entra() {
    let (base, items) = sobe();

    let status = post_texto(&base, None, "olá");

    assert_eq!(status, 401);
    assert!(textos(&items).is_empty());
}

#[test]
fn post_com_token_errado_e_recusado_e_nada_entra() {
    let (base, items) = sobe();

    let status = post_texto(&base, Some("outro-token"), "olá");

    assert_eq!(status, 401);
    assert!(textos(&items).is_empty());
}

#[test]
fn dois_posts_preservam_ordem_de_chegada() {
    let (base, items) = sobe();

    post_texto(&base, Some(TOKEN), "primeiro");
    post_texto(&base, Some(TOKEN), "segundo");

    assert_eq!(textos(&items), vec!["primeiro", "segundo"]);
}
