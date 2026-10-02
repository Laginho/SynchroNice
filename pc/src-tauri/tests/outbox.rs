use pc_lib::outbox::{OutBody, Outbox};

#[test]
fn arquivo_enviado_mantem_original_na_sessao_antes_e_depois_do_ack() {
    let raiz = tempfile::tempdir().unwrap();
    let origem = raiz.path().join("relatorio.pdf");
    std::fs::write(&origem, b"conteudo original").unwrap();
    let fila = Outbox::new(raiz.path().join("outbox")).unwrap();

    let item = fila.enqueue_file_from_path(&origem).unwrap();
    let enviados = fila.session_items();
    assert_eq!(enviados.len(), 1);
    assert_eq!(enviados[0].item, item);
    assert!(!enviados[0].entregue);
    assert_eq!(fila.session_file_path(item.id), Some(origem.clone()));
    assert_eq!(
        item.body,
        OutBody::File {
            name: "relatorio.pdf".into(),
            size: 17,
        }
    );

    // A origem pode sumir enquanto o celular está fora: a cópia de entrega
    // continua disponível e o caminho da bolha não muda.
    std::fs::remove_file(&origem).unwrap();
    assert_eq!(fila.file_bytes(item.id).unwrap(), b"conteudo original");
    fila.confirm(item.id);
    fila.confirm(item.id); // ACK repetido não duplica a bolha.
    let enviados = fila.session_items();
    assert_eq!(enviados.len(), 1);
    assert!(enviados[0].entregue);
    assert_eq!(enviados[0].item, item);
    assert_eq!(fila.session_file_path(item.id), Some(origem.clone()));
    assert!(fila.pending().is_empty());
    assert!(fila.file_bytes(item.id).is_err());

    std::fs::write(&origem, b"voltou").unwrap();
    assert_eq!(std::fs::read(fila.session_file_path(item.id).unwrap()).unwrap(), b"voltou");
}
