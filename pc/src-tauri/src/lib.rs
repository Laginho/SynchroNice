pub mod config;
pub mod items;
pub mod server;

use items::{Item, Items};
use tauri::menu::{Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{Emitter, Manager, State, WindowEvent};
use tauri_plugin_autostart::{MacosLauncher, ManagerExt};
use tauri_plugin_dialog::DialogExt;

/// Pede a Pasta se ainda não houver uma escolhida. Não-bloqueante: `setup` roda
/// na thread principal, onde `blocking_pick_folder` travaria o event loop.
/// Cancelar não grava nada — pergunta de novo na próxima abertura.
fn ask_pasta_on_first_run(app: &tauri::AppHandle) {
    let Ok(config_dir) = app.path().app_config_dir() else {
        eprintln!("sem diretório de configuração: a Pasta não será lembrada");
        return;
    };

    if config::load_pasta(&config_dir).is_some() {
        return;
    }

    app.dialog().file().pick_folder(move |pasta| {
        let Some(pasta) = pasta.and_then(|p| p.into_path().ok()) else {
            return;
        };
        if let Err(e) = config::save_pasta(&config_dir, &pasta) {
            eprintln!("não foi possível gravar a Pasta: {e}");
        }
    });
}

/// Evento que a janela escuta para desenhar a bolha de um Item novo.
const ITEM_EVENT: &str = "item";

/// A Sessão atual, para a janela se alinhar ao abrir (ou reabrir).
#[tauri::command]
fn list_items(items: State<'_, Items>) -> Vec<Item> {
    items.snapshot()
}

/// Sobe o servidor HTTP com o token do Par e liga a lista de Itens à janela.
fn start_server(app: &tauri::AppHandle, items: Items) {
    let token = app
        .path()
        .app_config_dir()
        .map_err(std::io::Error::other)
        .and_then(|dir| config::load_or_create_token(&dir));
    let token = match token {
        Ok(token) => token,
        Err(e) => {
            eprintln!("sem token do Par, o servidor não sobe: {e}");
            return;
        }
    };

    let handle = app.clone();
    items.on_new(move |item| {
        if let Err(e) = handle.emit(ITEM_EVENT, item) {
            eprintln!("não foi possível avisar a janela do Item {}: {e}", item.id);
        }
    });

    let addr = std::net::SocketAddr::from(([0, 0, 0, 0], server::PORT));
    match server::spawn(addr, token, items) {
        Ok(running) => println!("servidor HTTP em {}", running.addr),
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
        .manage(Items::new())
        .invoke_handler(tauri::generate_handler![list_items])
        .setup(|app| {
            let handle = app.handle().clone();
            start_server(&handle, app.state::<Items>().inner().clone());
            // Idempotente, e fora do primeiro uso: um perfil que já tem Pasta
            // gravada também precisa estar no autostart.
            if let Err(e) = handle.autolaunch().enable() {
                eprintln!("não foi possível registrar no autostart: {e}");
            }
            ask_pasta_on_first_run(&handle);
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
