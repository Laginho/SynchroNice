use pc_lib::config;

#[test]
fn ausencia_de_config_devolve_nao_escolhida() {
    let dir = tempfile::tempdir().unwrap();
    assert_eq!(config::load_pasta(dir.path()), None);
}

#[test]
fn grava_e_rele_o_caminho_da_pasta() {
    let dir = tempfile::tempdir().unwrap();
    let pasta = dir.path().join("Pasta SynchroNice");

    config::save_pasta(dir.path(), &pasta).unwrap();

    assert_eq!(config::load_pasta(dir.path()), Some(pasta));
}

#[test]
fn token_e_gerado_uma_vez_e_persiste() {
    let dir = tempfile::tempdir().unwrap();

    let primeiro = config::load_or_create_token(dir.path()).unwrap();
    let segundo = config::load_or_create_token(dir.path()).unwrap();

    assert!(primeiro.len() >= 16, "token curto demais: {primeiro:?}");
    assert_eq!(primeiro, segundo);
}

#[test]
fn token_e_pasta_convivem_na_mesma_configuracao() {
    let dir = tempfile::tempdir().unwrap();
    let pasta = dir.path().join("Pasta SynchroNice");

    config::save_pasta(dir.path(), &pasta).unwrap();
    let token = config::load_or_create_token(dir.path()).unwrap();
    config::save_pasta(dir.path(), &pasta).unwrap();

    assert_eq!(config::load_pasta(dir.path()), Some(pasta));
    assert_eq!(config::load_or_create_token(dir.path()).unwrap(), token);
}
