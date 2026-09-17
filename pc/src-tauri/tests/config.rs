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
