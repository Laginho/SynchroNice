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
    assert_eq!(
        std::fs::read(fila.session_file_path(item.id).unwrap()).unwrap(),
        b"voltou"
    );
}

#[test]
fn textos_e_prints_conservam_a_sessao_ate_o_processo_acabar() {
    let raiz = tempfile::tempdir().unwrap();
    let dir = raiz.path().join("outbox");
    let fila = Outbox::new(dir.clone()).unwrap();
    let texto = fila.enqueue_text("texto da Sessão".into()).unwrap();
    let print = fila.enqueue_file("print.png".into(), b"imagem").unwrap();
    let vazio = fila.enqueue_file("print.png".into(), b"").unwrap();
    let enviados = fila.session_items();
    assert_eq!(enviados.len(), 3);
    assert_eq!(enviados[0].item, texto);
    assert_eq!(enviados[1].item, print);
    assert_eq!(enviados[2].item, vazio);
    assert!(enviados.iter().all(|sent| !sent.entregue));
    assert!(fila.session_file_path(texto.id).is_none());
    let copia = fila.session_file_path(print.id).unwrap();
    let copia_vazia = fila.session_file_path(vazio.id).unwrap();
    assert!(copia.starts_with(&dir));
    assert_eq!(copia.file_name().unwrap(), "print.png");
    assert_eq!(copia_vazia.file_name().unwrap(), "print.png");
    assert_ne!(copia, copia_vazia);
    assert_eq!(std::fs::read(&copia).unwrap(), b"imagem");
    assert_eq!(std::fs::read(&copia_vazia).unwrap(), b"");

    fila.confirm(texto.id);
    fila.confirm(print.id);
    let enviados = fila.session_items();
    assert!(enviados[0].entregue);
    assert!(enviados[1].entregue);
    assert!(!enviados[2].entregue);
    assert_eq!(std::fs::read(&copia).unwrap(), b"imagem");
    assert_eq!(fila.pending(), vec![vazio.clone()]);
    assert!(fila.file_bytes(print.id).is_err());

    // Simula um processo que morreu sem executar a limpeza de encerramento.
    std::mem::forget(fila);
    let recriada = Outbox::new(dir).unwrap();
    assert!(!copia.exists());
    assert!(!copia_vazia.exists());
    assert!(recriada.session_items().is_empty());
    assert!(recriada.session_file_path(vazio.id).is_none());
    assert_eq!(recriada.pending(), vec![vazio.clone()]);
    assert_eq!(recriada.file_bytes(vazio.id).unwrap(), b"");

    let avisos = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
    let captura = avisos.clone();
    recriada.on_change(move |item, entregue| {
        captura.lock().unwrap().push((item.id, entregue));
    });
    recriada.confirm(vazio.id);
    assert!(avisos.lock().unwrap().is_empty(), "ACK herdado não cria bolha");
    assert!(recriada.session_items().is_empty());
    let nova = recriada.enqueue_text("nova Sessão".into()).unwrap();
    assert!(nova.id > vazio.id);
    assert_eq!(recriada.session_items()[0].item, nova);
}

#[test]
fn copia_de_print_dura_ate_a_ultima_alca_da_sessao() {
    let raiz = tempfile::tempdir().unwrap();
    let fila = Outbox::new(raiz.path().join("outbox")).unwrap();
    let item = fila.enqueue_file("print.png".into(), b"imagem").unwrap();
    let copia = fila.session_file_path(item.id).unwrap();
    let outra_alca = fila.clone();
    drop(fila);
    assert!(copia.is_file());
    drop(outra_alca);
    assert!(!copia.exists());

    let recriada = Outbox::new(raiz.path().join("outbox")).unwrap();
    assert_eq!(recriada.file_bytes(item.id).unwrap(), b"imagem");
}

#[test]
fn nome_de_print_nao_pode_escapar_da_pasta_da_sessao() {
    let raiz = tempfile::tempdir().unwrap();
    let fila = Outbox::new(raiz.path().join("outbox")).unwrap();
    for nome in ["../fora.png", "..\\fora.png", "C:\\fora.png", "", ".", ".."] {
        assert!(fila.enqueue_file(nome.into(), b"imagem").is_err(), "{nome}");
        assert!(fila.pending().is_empty());
        assert!(fila.session_items().is_empty());
    }
}
