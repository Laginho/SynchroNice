//! `drag::resolve` é a única lógica própria do comando de arrastar (SYN-006):
//! o resto é integração com o shell do Windows, sem teste automático viável.

use pc_lib::drag;

#[test]
fn resolve_devolve_o_caminho_de_um_arquivo_que_existe_na_pasta() {
    let dir = tempfile::tempdir().unwrap();
    let caminho = dir.path().join("foto.png");
    std::fs::write(&caminho, b"bytes").unwrap();

    assert_eq!(drag::resolve(dir.path(), "foto.png"), Some(caminho));
}

#[test]
fn resolve_devolve_none_se_o_arquivo_nao_esta_mais_na_pasta() {
    let dir = tempfile::tempdir().unwrap();

    assert_eq!(drag::resolve(dir.path(), "sumiu.png"), None);
}
