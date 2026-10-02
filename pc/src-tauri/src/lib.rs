pub mod anuncio;
pub mod config;
pub mod items;
pub mod outbox;
pub mod par;
pub mod qr;
pub mod server;

use items::{Item, Items};
use outbox::{OutItem, Outbox};
use par::Par;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use tauri::menu::{Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{Emitter, Manager, State, WindowEvent};
use tauri_plugin_autostart::{MacosLauncher, ManagerExt};
use tauri_plugin_dialog::DialogExt;

/// Evento que a janela escuta para desenhar (ou atualizar) a bolha de um
/// Item mandado ao celular.
const SENT_EVENT: &str = "enviado";

/// Evento que a janela escuta para o indicador de presença: `true` enquanto
/// há uma conexão `/listen` viva, `false` quando ela cai.
const PRESENCE_EVENT: &str = "presenca";

/// Evento que a janela escuta quando Itens saem da Sessão por uma exclusão
/// (do celular, ou o Apagar tudo da própria janela): payload `DeletedItems`.
const DELETED_EVENT: &str = "apagado";

/// Payload do evento `enviado`: o Item mais se ele já foi confirmado pelo
/// celular (Entregue) ou ainda está Pendente.
#[derive(Clone, serde::Serialize)]
struct Enviado {
    #[serde(flatten)]
    item: OutItem,
    entregue: bool,
    ordem: usize,
}

#[derive(Clone, serde::Serialize)]
struct Recebido {
    #[serde(flatten)]
    item: Item,
    ordem: usize,
}

/// Desempate de horário entre os dois espaços de ids, conservado no backend
/// para que recarregar mantenha a ordem em que as bolhas entraram na Sessão.
#[derive(Clone, Default)]
struct SessionOrder(Arc<Mutex<HashMap<(bool, u64), usize>>>);

impl SessionOrder {
    fn of(&self, enviado: bool, id: u64) -> usize {
        let mut order = self.0.lock().expect("ordem da Sessão sem panic");
        let next = order.len();
        *order.entry((enviado, id)).or_insert(next)
    }
}

/// Se um Par já estava gravado antes desta abertura do app — só para decidir
/// se a janela abre na tela de QR (SYN-005). Guardado à parte do `Par` em si
/// porque `Par::load_or_create` sempre devolve um (cria na primeira vez).
struct ParJaExistia(bool);

/// Devolvido ao abrir a janela: se ela deve abrir na tela de QR, e o QR do
/// Par atual, para o caso de precisar mostrá-lo.
#[derive(serde::Serialize)]
struct EstadoParInicial {
    mostrar_qr: bool,
    svg: String,
}

fn pasta_efetiva(app: &tauri::AppHandle, config_dir: &Path) -> Result<PathBuf, String> {
    config::pasta_efetiva(config_dir, || {
        app.path().download_dir().map_err(|e| e.to_string())
    })
}

/// Evento que a janela escuta para desenhar a bolha de um Item novo.
const ITEM_EVENT: &str = "item";

/// A Sessão atual, para a janela se alinhar ao abrir (ou reabrir).
#[tauri::command]
fn list_items(items: State<'_, Items>, order: State<'_, SessionOrder>) -> Vec<Recebido> {
    items
        .snapshot()
        .into_iter()
        .map(|item| Recebido {
            ordem: order.of(false, item.id),
            item,
        })
        .collect()
}

/// Só enviadas desta Sessão, inclusive as Entregues; nunca restaura o chat
/// a partir do manifesto persistido de Pendentes.
#[tauri::command]
fn list_sent_items(outbox: State<'_, Outbox>, order: State<'_, SessionOrder>) -> Vec<Enviado> {
    outbox
        .session_items()
        .into_iter()
        .map(|sent| Enviado {
            ordem: order.of(true, sent.item.id),
            item: sent.item,
            entregue: sent.entregue,
        })
        .collect()
}

/// Se a janela deve abrir na tela de QR (Par ainda não existia antes desta
/// abertura), e o QR do Par atual — pronto se for preciso mostrá-lo depois
/// (SYN-005).
#[tauri::command]
fn estado_par_inicial(
    par: State<'_, Par>,
    ja_existia: State<'_, ParJaExistia>,
) -> EstadoParInicial {
    EstadoParInicial {
        mostrar_qr: !ja_existia.0,
        svg: qr::par_svg(
            par.nome_instancia(),
            server::PORT,
            &par.token(),
            qr::ip_da_lan(),
        ),
    }
}

/// "Parear de novo": rotaciona o token do Par e devolve o QR com o token
/// novo — o anterior passa a ser recusado a partir desta chamada.
#[tauri::command]
fn parear_de_novo(par: State<'_, Par>, outbox: State<'_, Outbox>) -> Result<String, String> {
    par.rotate(&outbox).map_err(|e| e.to_string())?;
    Ok(qr::par_svg(
        par.nome_instancia(),
        server::PORT,
        &par.token(),
        qr::ip_da_lan(),
    ))
}

/// Abre o seletor nativo e aplica a nova Pasta ao servidor sem reiniciá-lo.
#[tauri::command]
async fn mudar_pasta(app: tauri::AppHandle) -> Result<bool, String> {
    let (tx, rx) = tokio::sync::oneshot::channel();
    app.dialog().file().pick_folder(move |selection| {
        let pasta = selection.and_then(|path| path.into_path().ok());
        let _ = tx.send(pasta);
    });

    let Some(pasta) = rx.await.map_err(|e| e.to_string())? else {
        return Ok(false);
    };
    let config_dir = app
        .path()
        .app_config_dir()
        .map(|dir| config::resolve_data_dir(dir, std::env::var_os("SYNCHRONICE_DATA_DIR")).0)
        .map_err(|e| e.to_string())?;
    let running = app
        .try_state::<server::Running>()
        .ok_or_else(|| "servidor HTTP não está disponível".to_string())?;
    running
        .escolher_pasta(&config_dir, &pasta)
        .map_err(|e| e.to_string())?;
    Ok(true)
}

/// Fim da janela de Desfazer do Apagar tudo: `received` são ids de `Items`,
/// `sent` as chaves da lista de enviados.
#[tauri::command]
async fn apagar_tudo(
    app: tauri::AppHandle,
    received: Vec<u64>,
    sent: Vec<u64>,
) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || {
        app.try_state::<server::Running>()
            .ok_or_else(|| "servidor HTTP não está disponível".to_string())?
            .apagar_tudo(&received, &sent)
            .map_err(|e| e.to_string())
    })
    .await
    .map_err(|e| e.to_string())?
}

/// Caminho do arquivo recebido de um Item que terminou de gravar. A consulta
/// por id mantém os Itens antigos ligados à Pasta em que chegaram.
fn caminho_do_arquivo_recebido(app: &tauri::AppHandle, id: u64) -> Result<PathBuf, String> {
    app.try_state::<server::Running>()
        .ok_or_else(|| "servidor HTTP não está disponível".to_string())?
        .caminho_do_arquivo(id)
        .filter(|caminho| caminho.is_file())
        .ok_or_else(|| "o arquivo não está mais na Pasta".to_string())
}

/// Bytes de um arquivo recebido, para a miniatura de imagem na bolha.
#[tauri::command]
fn read_file_bytes(app: tauri::AppHandle, id: u64) -> Result<Vec<u8>, String> {
    let caminho = caminho_do_arquivo_recebido(&app, id)?;
    std::fs::read(caminho).map_err(|e| e.to_string())
}

/// Caminho absoluto do arquivo de uma bolha recebida, para o drag nativo do
/// SO (SYN-006). Se o arquivo não está mais lá, a janela não chama o drag e
/// marca a bolha como sumida, em vez de arrastar um caminho que não existe.
#[tauri::command]
fn caminho_para_arrastar(app: tauri::AppHandle, id: u64) -> Result<String, String> {
    caminho_do_arquivo_recebido(&app, id)?
        .to_str()
        .map(str::to_string)
        .ok_or_else(|| "o caminho do arquivo não é válido".to_string())
}

fn caminho_do_arquivo_enviado(outbox: &Outbox, id: u64) -> Result<PathBuf, String> {
    outbox
        .session_file_path(id)
        .filter(|path| path.is_file())
        .ok_or_else(|| "o arquivo de origem não está mais disponível".to_string())
}

#[tauri::command]
fn read_sent_file_bytes(outbox: State<'_, Outbox>, id: u64) -> Result<Vec<u8>, String> {
    std::fs::read(caminho_do_arquivo_enviado(&outbox, id)?).map_err(|e| e.to_string())
}

#[tauri::command]
fn caminho_enviado_para_arrastar(outbox: State<'_, Outbox>, id: u64) -> Result<String, String> {
    caminho_do_arquivo_enviado(&outbox, id)?
        .to_str()
        .map(str::to_string)
        .ok_or_else(|| "o caminho do arquivo não é válido".to_string())
}

/// Extensões que o Windows executa ao abrir: um clique na bolha mostra o
/// arquivo no Explorer em vez de rodar o que veio da rede.
const EXECUTAVEIS: &[&str] = &[
    "exe", "msi", "bat", "cmd", "com", "scr", "pif", "ps1", "vbs", "vbe", "js", "jse", "wsf",
    "wsh", "hta", "cpl", "msc", "lnk", "reg", "jar",
];

fn executavel(caminho: &std::path::Path) -> bool {
    caminho
        .extension()
        .and_then(|e| e.to_str())
        .is_some_and(|e| EXECUTAVEIS.contains(&e.to_ascii_lowercase().as_str()))
}

/// Clique numa bolha recebida: abre o arquivo no app padrão do Windows, ou
/// mostra no Explorer se for executável.
#[tauri::command]
fn abrir_arquivo(app: tauri::AppHandle, id: u64) -> Result<(), String> {
    abrir_caminho(&app, caminho_do_arquivo_recebido(&app, id)?)
}

#[tauri::command]
fn abrir_arquivo_enviado(
    app: tauri::AppHandle,
    outbox: State<'_, Outbox>,
    id: u64,
) -> Result<(), String> {
    abrir_caminho(&app, caminho_do_arquivo_enviado(&outbox, id)?)
}

fn abrir_caminho(app: &tauri::AppHandle, caminho: PathBuf) -> Result<(), String> {
    use tauri_plugin_opener::OpenerExt;
    if executavel(&caminho) {
        app.opener().reveal_item_in_dir(&caminho)
    } else {
        app.opener()
            .open_path(caminho.to_string_lossy(), None::<&str>)
    }
    .map_err(|e| e.to_string())
}

/// Enter no composer: enfileira o texto para o celular.
#[tauri::command]
fn send_text(outbox: State<'_, Outbox>, text: String) -> Result<(), String> {
    outbox
        .enqueue_text(text)
        .map(|_| ())
        .map_err(|e| e.to_string())
}

/// Drop de arquivo na janela, ou Ctrl+V de arquivos copiados no Explorer:
/// `paths` são caminhos no disco do PC, um Item por caminho (nunca
/// agrupados, spec "Enviar do PC").
#[tauri::command]
fn send_files(outbox: State<'_, Outbox>, paths: Vec<String>) -> Result<(), String> {
    for caminho in paths {
        outbox
            .enqueue_file_from_path(Path::new(&caminho))
            .map_err(|e| e.to_string())?;
    }
    Ok(())
}

/// Ctrl+V com uma imagem no clipboard: `bytes` já é o PNG, lido no lado
/// TypeScript pela Clipboard API do WebView2 — sem caminho no disco, ao
/// contrário de `send_files`.
#[tauri::command]
fn send_bytes(outbox: State<'_, Outbox>, name: String, bytes: Vec<u8>) -> Result<(), String> {
    outbox
        .enqueue_file(name, &bytes)
        .map(|_| ())
        .map_err(|e| e.to_string())
}

/// Lista de caminhos de arquivo do clipboard do Windows (Ctrl+C num arquivo
/// no Explorer) — formato `CF_HDROP`, que a Clipboard API da web não expõe.
#[tauri::command]
fn read_clipboard_file_list() -> Result<Vec<String>, String> {
    clipboard_win::get_clipboard(clipboard_win::formats::FileList).map_err(|e| e.to_string())
}

#[derive(serde::Serialize)]
struct PreviaDeCaminho {
    tamanho: u64,
    bytes: Option<Vec<u8>>,
}

/// Prévia somente leitura: nunca usa a fila nem cria cópias de entrega.
#[tauri::command]
fn previa_de_caminho(caminho: String) -> Result<PreviaDeCaminho, String> {
    use std::io::Read;
    const LIMITE: u64 = 10 * 1024 * 1024;
    let caminho = Path::new(&caminho);
    let arquivo = std::fs::File::open(caminho).map_err(|e| e.to_string())?;
    let metadata = arquivo.metadata().map_err(|e| e.to_string())?;
    if !metadata.is_file() {
        return Err("a origem não é um arquivo".to_string());
    }
    let imagem = caminho
        .extension()
        .and_then(|ext| ext.to_str())
        .is_some_and(|ext| {
            ["jpg", "jpeg", "png", "gif", "webp", "bmp"]
                .contains(&ext.to_ascii_lowercase().as_str())
        });
    let bytes = if imagem && metadata.len() <= LIMITE {
        let mut bytes = Vec::new();
        // Também limita o caso em que o arquivo cresce depois do metadata.
        arquivo
            .take(LIMITE + 1)
            .read_to_end(&mut bytes)
            .map_err(|e| e.to_string())?;
        (bytes.len() as u64 <= LIMITE).then_some(bytes)
    } else {
        None
    };
    Ok(PreviaDeCaminho {
        tamanho: metadata.len(),
        bytes,
    })
}

/// Sobe o servidor HTTP com o token do Par, anuncia o serviço por mDNS, liga
/// a lista de Itens e a fila de saída à janela, e disponibiliza a fila para
/// os comandos de envio.
fn start_server(app: &tauri::AppHandle, items: Items) {
    let config_dir = match app
        .path()
        .app_config_dir()
        .map(|dir| config::resolve_data_dir(dir, std::env::var_os("SYNCHRONICE_DATA_DIR")).0)
    {
        Ok(dir) => dir,
        Err(e) => {
            eprintln!("sem diretório de configuração, o servidor não sobe: {e}");
            return;
        }
    };
    let par_ja_existia = config::par_ja_existe(&config_dir);
    let par = match Par::load_or_create(&config_dir) {
        Ok(par) => par,
        Err(e) => {
            eprintln!("sem Par, o servidor não sobe: {e}");
            return;
        }
    };
    app.manage(par.clone());
    app.manage(ParJaExistia(par_ja_existia));

    let pasta = match pasta_efetiva(app, &config_dir) {
        Ok(pasta) => Some(pasta),
        Err(e) => {
            eprintln!("sem Pasta efetiva; o servidor subirá sem Pasta: {e}");
            None
        }
    };

    let outbox = match Outbox::new(config_dir.join("outbox")) {
        Ok(outbox) => outbox,
        Err(e) => {
            eprintln!("sem fila de saída, o servidor não sobe: {e}");
            return;
        }
    };
    app.manage(outbox.clone());

    let handle = app.clone();
    let order = app.state::<SessionOrder>().inner().clone();
    items.on_new(move |item| {
        let payload = Recebido {
            ordem: order.of(false, item.id),
            item: item.clone(),
        };
        if let Err(e) = handle.emit(ITEM_EVENT, payload) {
            eprintln!("não foi possível avisar a janela do Item {}: {e}", item.id);
        }
    });

    let handle = app.clone();
    items.on_delete(move |deleted| {
        if let Err(e) = handle.emit(DELETED_EVENT, deleted) {
            eprintln!("não foi possível avisar a janela da exclusão: {e}");
        }
    });

    let handle = app.clone();
    let order = app.state::<SessionOrder>().inner().clone();
    outbox.on_change(move |item, entregue| {
        let payload = Enviado {
            item: item.clone(),
            entregue,
            ordem: order.of(true, item.id),
        };
        if let Err(e) = handle.emit(SENT_EVENT, payload) {
            eprintln!("não foi possível avisar a janela do envio {}: {e}", item.id);
        }
    });

    let addr = std::net::SocketAddr::from(([0, 0, 0, 0], server::PORT));
    let accepted_ids = config_dir.join("accepted-file-ids.txt");
    match server::spawn_with_accepted_ids(addr, par.clone(), items, outbox, pasta, &accepted_ids) {
        Ok(running) => {
            println!("servidor HTTP em {}", running.addr);
            match anuncio::Anuncio::subir(par.nome_instancia(), server::PORT) {
                Ok(anuncio) => {
                    app.manage(anuncio);
                }
                Err(e) => eprintln!("não foi possível anunciar o serviço por mDNS: {e}"),
            }
            let handle = app.clone();
            running.presence.on_change(move |online| {
                if let Err(e) = handle.emit(PRESENCE_EVENT, online) {
                    eprintln!("não foi possível avisar a janela da presença: {e}");
                }
            });
            app.manage(running);
        }
        Err(e) => eprintln!("servidor HTTP não subiu em {addr}: {e}"),
    }
}

fn setup_tray(app: &tauri::AppHandle) -> tauri::Result<()> {
    let abrir = MenuItem::with_id(app, "abrir", "Abrir", true, None::<&str>)?;
    let sair = MenuItem::with_id(app, "sair", "Sair", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&abrir, &sair])?;

    TrayIconBuilder::new()
        .icon(app.default_window_icon().unwrap().clone())
        .menu(&menu)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "abrir" => {
                if let Some(window) = app.get_webview_window("main") {
                    let _ = window.show();
                    let _ = window.set_focus();
                }
            }
            "sair" => app.exit(0),
            _ => {}
        })
        .build(app)?;

    Ok(())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let (_, autostart) =
        config::resolve_data_dir(PathBuf::new(), std::env::var_os("SYNCHRONICE_DATA_DIR"));
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_autostart::init(
            MacosLauncher::LaunchAgent,
            None,
        ))
        .plugin(tauri_plugin_drag::init())
        .manage(Items::new())
        .manage(SessionOrder::default())
        .invoke_handler(tauri::generate_handler![
            list_items,
            list_sent_items,
            estado_par_inicial,
            parear_de_novo,
            mudar_pasta,
            apagar_tudo,
            read_file_bytes,
            read_sent_file_bytes,
            send_text,
            send_files,
            send_bytes,
            read_clipboard_file_list,
            previa_de_caminho,
            caminho_para_arrastar,
            caminho_enviado_para_arrastar,
            abrir_arquivo,
            abrir_arquivo_enviado
        ])
        .setup(move |app| {
            let handle = app.handle().clone();
            start_server(&handle, app.state::<Items>().inner().clone());
            // Idempotente, e fora do primeiro uso: um perfil que já tem Pasta
            // gravada também precisa estar no autostart.
            if autostart {
                if let Err(e) = handle.autolaunch().enable() {
                    eprintln!("não foi possível registrar no autostart: {e}");
                }
            }
            setup_tray(&handle)?;
            Ok(())
        })
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                let _ = window.hide();
                api.prevent_close();
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

#[cfg(test)]
mod tests {
    #[test]
    fn previa_de_caminho_le_tamanho_e_limita_bytes_a_imagens_pequenas() {
        let dir = tempfile::tempdir().unwrap();
        let documento = dir.path().join("documento.txt");
        let imagem = dir.path().join("foto.PNG");
        let grande = dir.path().join("grande.png");
        std::fs::write(&documento, b"texto").unwrap();
        std::fs::write(&imagem, b"imagem").unwrap();
        let arquivo = std::fs::File::create(&grande).unwrap();
        arquivo.set_len(10 * 1024 * 1024 + 1).unwrap();

        let previa = super::previa_de_caminho(documento.to_string_lossy().into_owned()).unwrap();
        assert_eq!(previa.tamanho, 5);
        assert_eq!(previa.bytes, None);
        let previa = super::previa_de_caminho(imagem.to_string_lossy().into_owned()).unwrap();
        assert_eq!(previa.tamanho, 6);
        assert_eq!(previa.bytes.as_deref(), Some(b"imagem".as_slice()));
        std::fs::File::create(&imagem)
            .unwrap()
            .set_len(10 * 1024 * 1024)
            .unwrap();
        let previa = super::previa_de_caminho(imagem.to_string_lossy().into_owned()).unwrap();
        assert_eq!(previa.bytes.unwrap().len(), 10 * 1024 * 1024);
        let previa = super::previa_de_caminho(grande.to_string_lossy().into_owned()).unwrap();
        assert_eq!(previa.tamanho, 10 * 1024 * 1024 + 1);
        assert_eq!(previa.bytes, None);
        assert!(super::previa_de_caminho(
            dir.path().join("sumiu.png").to_string_lossy().into_owned()
        )
        .is_err());
        assert!(super::previa_de_caminho(dir.path().to_string_lossy().into_owned()).is_err());
        assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 3);
    }

    #[test]
    fn snapshot_da_janela_esconde_upload_ja_selecionado_para_exclusao() {
        let items = super::Items::new();
        let enviando = items.start_file("a.bin".into(), 100);
        let concluido = items.start_file("b.bin".into(), 100);
        items.file_done(concluido.id);
        let texto = items.push_text("fica".into());

        items.forget(&[enviando.id, concluido.id], vec![], false);

        let ids: Vec<u64> = super::recebidos_visiveis(&items)
            .iter()
            .map(|item| item.id)
            .collect();
        assert_eq!(ids, vec![texto.id]);
        assert!(items.delete_requested(enviando.id));
    }

    #[test]
    fn snapshot_da_janela_mantem_upload_nao_selecionado() {
        let items = super::Items::new();
        let enviando = items.start_file("a.bin".into(), 100);
        let outro = items.start_file("b.bin".into(), 100);

        items.forget(&[outro.id], vec![], false);

        let ids: Vec<u64> = super::recebidos_visiveis(&items)
            .iter()
            .map(|item| item.id)
            .collect();
        assert_eq!(ids, vec![enviando.id]);
    }

    use super::executavel;
    use std::path::Path;

    #[test]
    fn executavel_nao_abre_direto() {
        for nome in ["virus.exe", "script.PS1", "atalho.lnk", "x.tar.bat"] {
            assert!(executavel(Path::new(nome)), "{nome}");
        }
        for nome in ["foto.jpg", "doc.pdf", "sem_extensao", "exe"] {
            assert!(!executavel(Path::new(nome)), "{nome}");
        }
    }
}
