pub mod anuncio;
pub mod config;
pub mod drag;
pub mod items;
pub mod outbox;
pub mod par;
pub mod qr;
pub mod server;

use items::{Item, Items};
use outbox::{OutItem, Outbox};
use par::Par;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{Emitter, Manager, State, WindowEvent};
use tauri_plugin_autostart::{MacosLauncher, ManagerExt};
use tauri_plugin_dialog::DialogExt;
use std::path::{Path, PathBuf};

/// Evento que a janela escuta para desenhar (ou atualizar) a bolha de um
/// Item mandado ao celular.
const SENT_EVENT: &str = "enviado";

/// Evento que a janela escuta para o indicador de presença: `true` enquanto
/// há uma conexão `/listen` viva, `false` quando ela cai.
const PRESENCE_EVENT: &str = "presenca";

/// Payload do evento `enviado`: o Item mais se ele já foi confirmado pelo
/// celular (Entregue) ou ainda está Pendente.
#[derive(Clone, serde::Serialize)]
struct Enviado {
    #[serde(flatten)]
    item: OutItem,
    entregue: bool,
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
    let downloads_dir = app.path().download_dir().map_err(|e| e.to_string())?;
    Ok(config::pasta_efetiva(config_dir, &downloads_dir))
}

/// Evento que a janela escuta para desenhar a bolha de um Item novo.
const ITEM_EVENT: &str = "item";

/// A Sessão atual, para a janela se alinhar ao abrir (ou reabrir).
#[tauri::command]
fn list_items(items: State<'_, Items>) -> Vec<Item> {
    items.snapshot()
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
        svg: qr::par_svg(par.nome_instancia(), server::PORT, &par.token()),
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
    let config_dir = app.path().app_config_dir().map_err(|e| e.to_string())?;
    let running = app
        .try_state::<server::Running>()
        .ok_or_else(|| "servidor HTTP não está disponível".to_string())?;
    running
        .escolher_pasta(&config_dir, &pasta)
        .map_err(|e| e.to_string())?;
    Ok(true)
}

/// Bytes de um arquivo já gravado na Pasta, para a miniatura de imagem na
/// bolha. `name` é o nome final do Item (já saneado ao gravar); saneado de
/// novo aqui para nunca ler fora da Pasta.
#[tauri::command]
fn read_file_bytes(app: tauri::AppHandle, name: String) -> Result<Vec<u8>, String> {
    let config_dir = app.path().app_config_dir().map_err(|e| e.to_string())?;
    let pasta = pasta_efetiva(&app, &config_dir)?;
    std::fs::read(pasta.join(server::sanitize_file_name(&name))).map_err(|e| e.to_string())
}

/// Caminho absoluto do arquivo de uma bolha recebida, para o drag nativo do
/// SO (SYN-006). Se o arquivo não está mais lá, a janela não chama o drag e
/// marca a bolha como sumida, em vez de arrastar um caminho que não existe.
#[tauri::command]
fn caminho_para_arrastar(app: tauri::AppHandle, name: String) -> Result<String, String> {
    let config_dir = app.path().app_config_dir().map_err(|e| e.to_string())?;
    let pasta = pasta_efetiva(&app, &config_dir)?;
    drag::resolve(&pasta, &name)
        .and_then(|caminho| caminho.to_str().map(str::to_string))
        .ok_or_else(|| "o arquivo não está mais na Pasta".to_string())
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
        let bytes = std::fs::read(&caminho).map_err(|e| e.to_string())?;
        let nome = std::path::Path::new(&caminho)
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("arquivo")
            .to_string();
        outbox
            .enqueue_file(nome, &bytes)
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

/// Sobe o servidor HTTP com o token do Par, anuncia o serviço por mDNS, liga
/// a lista de Itens e a fila de saída à janela, e disponibiliza a fila para
/// os comandos de envio.
fn start_server(app: &tauri::AppHandle, items: Items) {
    let config_dir = match app.path().app_config_dir() {
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
        Ok(pasta) => pasta,
        Err(e) => {
            eprintln!("sem Pasta efetiva, o servidor não sobe: {e}");
            return;
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
    items.on_new(move |item| {
        if let Err(e) = handle.emit(ITEM_EVENT, item) {
            eprintln!("não foi possível avisar a janela do Item {}: {e}", item.id);
        }
    });

    let handle = app.clone();
    outbox.on_change(move |item, entregue| {
        let payload = Enviado {
            item: item.clone(),
            entregue,
        };
        if let Err(e) = handle.emit(SENT_EVENT, payload) {
            eprintln!("não foi possível avisar a janela do envio {}: {e}", item.id);
        }
    });

    let addr = std::net::SocketAddr::from(([0, 0, 0, 0], server::PORT));
    match server::spawn(addr, par.clone(), items, outbox, Some(pasta)) {
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
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_autostart::init(
            MacosLauncher::LaunchAgent,
            None,
        ))
        .plugin(tauri_plugin_drag::init())
        .manage(Items::new())
        .invoke_handler(tauri::generate_handler![
            list_items,
            estado_par_inicial,
            parear_de_novo,
            mudar_pasta,
            read_file_bytes,
            send_text,
            send_files,
            send_bytes,
            read_clipboard_file_list,
            caminho_para_arrastar
        ])
        .setup(|app| {
            let handle = app.handle().clone();
            start_server(&handle, app.state::<Items>().inner().clone());
            // Idempotente, e fora do primeiro uso: um perfil que já tem Pasta
            // gravada também precisa estar no autostart.
            if let Err(e) = handle.autolaunch().enable() {
                eprintln!("não foi possível registrar no autostart: {e}");
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
