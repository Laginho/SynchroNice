use pc_lib::config;
use std::path::PathBuf;

#[test]
fn diretorio_isolado_desliga_autostart_e_padrao_o_mantem() {
    let padrao = PathBuf::from("perfil real");
    let isolado = PathBuf::from("execucao QA/dados");
    assert_eq!(
        config::resolve_data_dir(padrao.clone(), Some(isolado.clone().into_os_string())),
        (isolado, false)
    );
    assert_eq!(
        config::resolve_data_dir(padrao.clone(), None),
        (padrao, true)
    );
}

#[test]
fn ausencia_de_config_devolve_nao_escolhida() {
    let dir = tempfile::tempdir().unwrap();
    assert_eq!(config::load_pasta(dir.path()), None);
}

#[test]
fn pasta_efetiva_sem_escolha_fica_em_synchronice_dentro_de_downloads() {
    let config_dir = tempfile::tempdir().unwrap();
    let downloads = tempfile::tempdir().unwrap();

    assert_eq!(
        config::pasta_efetiva(config_dir.path(), || {
            Ok::<_, std::io::Error>(downloads.path().to_path_buf())
        })
        .unwrap(),
        downloads.path().join("SynchroNice")
    );
}

#[test]
fn pasta_escolhida_continua_efetiva_quando_downloads_nao_pode_ser_resolvida() {
    let config_dir = tempfile::tempdir().unwrap();
    let pasta_escolhida = config_dir.path().join("Pasta escolhida");
    config::save_pasta(config_dir.path(), &pasta_escolhida).unwrap();
    let mut resolveu_downloads = false;

    let pasta = config::pasta_efetiva(config_dir.path(), || {
        resolveu_downloads = true;
        Err("Downloads indisponíveis")
    })
    .unwrap();

    assert_eq!(pasta, pasta_escolhida);
    assert!(!resolveu_downloads);
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
