//! A fila de saída: Itens que o PC quer mandar ao celular, persistidos em
//! disco até o celular confirmar o recebimento (spec, "Fila de Pendentes").
//!
//! O id de um Item é gerado pelo remetente — aqui, o PC — e viaja no
//! anúncio de `/listen`; só a confirmação por id remove o Item da fila, para
//! reenvio nunca duplicar bolha nem arquivo do outro lado (spec, "Protocolo
//! de Itens"). O id nunca é reaproveitado, mesmo depois de confirmações
//! removerem Itens: `next_id` persiste no manifesto para sobreviver a um
//! reinício sem colidir com um id já visto pelo celular.

use serde::{Deserialize, Serialize};
use std::io;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::sync::mpsc::{self, UnboundedReceiver, UnboundedSender};

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum OutBody {
    Text { text: String },
    File { name: String, size: u64 },
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct OutItem {
    pub id: u64,
    /// Hora de enfileiramento, em milissegundos desde a época Unix.
    pub at_ms: u64,
    #[serde(flatten)]
    pub body: OutBody,
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

const MANIFEST_FILE: &str = "manifest.json";

#[derive(Serialize, Deserialize)]
struct Manifest {
    next_id: u64,
    items: Vec<OutItem>,
}

impl Default for Manifest {
    fn default() -> Self {
        Self {
            next_id: 1,
            items: Vec::new(),
        }
    }
}

fn load_manifest(dir: &Path) -> Manifest {
    std::fs::read_to_string(dir.join(MANIFEST_FILE))
        .ok()
        .and_then(|data| serde_json::from_str(&data).ok())
        .unwrap_or_default()
}

fn save_manifest(dir: &Path, manifest: &Manifest) -> io::Result<()> {
    let data = serde_json::to_string_pretty(manifest).expect("Manifest serializa");
    std::fs::write(dir.join(MANIFEST_FILE), data)
}

/// Chamado quando um Item entra na fila (`entregue: false`) ou quando o
/// celular confirma o recebimento e ele sai (`entregue: true`) — é o que a
/// janela do PC escuta para desenhar a bolha à direita e trocá-la de
/// Pendente para Entregue.
type ChangeListener = Box<dyn Fn(&OutItem, bool) + Send + Sync>;

struct Inner {
    manifest: Manifest,
    /// Um remetente por conexão `/listen` viva, para anunciar um Item novo
    /// assim que ele é enfileirado. Descartados sob demanda: um `send` que
    /// falha (celular desconectou) tira o remetente da lista na próxima
    /// notificação, em vez de vazar para sempre.
    listeners: Vec<UnboundedSender<OutItem>>,
    changed: Vec<ChangeListener>,
}

fn notify(listeners: &mut Vec<UnboundedSender<OutItem>>, item: &OutItem) {
    listeners.retain(|tx| tx.send(item.clone()).is_ok());
}

fn notify_change(inner: &Inner, item: &OutItem, entregue: bool) {
    for listener in &inner.changed {
        listener(item, entregue);
    }
}

/// Fila compartilhada entre o servidor e a janela. Clonar dá outra alça para
/// a mesma fila e o mesmo diretório em disco.
#[derive(Clone)]
pub struct Outbox {
    dir: PathBuf,
    inner: Arc<Mutex<Inner>>,
}

impl Outbox {
    /// Carrega a fila persistida em `dir`, ou começa vazia se `dir` ainda
    /// não tiver um manifesto — mesmo diretório entre reinícios do processo
    /// é o que faz a fila sobreviver a sair e reabrir o app.
    pub fn new(dir: PathBuf) -> io::Result<Self> {
        std::fs::create_dir_all(&dir)?;
        let manifest = load_manifest(&dir);
        Ok(Self {
            dir,
            inner: Arc::new(Mutex::new(Inner {
                manifest,
                listeners: Vec::new(),
                changed: Vec::new(),
            })),
        })
    }

    fn file_path(&self, id: u64) -> PathBuf {
        self.dir.join(format!("{id}.bin"))
    }

    fn enqueue(&self, body: OutBody) -> io::Result<OutItem> {
        let mut inner = self.inner.lock().expect("fila de saída sem panic");
        let item = OutItem {
            id: inner.manifest.next_id,
            at_ms: now_ms(),
            body,
        };
        inner.manifest.next_id += 1;
        inner.manifest.items.push(item.clone());
        save_manifest(&self.dir, &inner.manifest)?;
        notify(&mut inner.listeners, &item);
        notify_change(&inner, &item, false);
        Ok(item)
    }

    /// Enfileira um Item de texto. Pendente até um celular confirmar o id
    /// pela rota de confirmação.
    pub fn enqueue_text(&self, text: String) -> io::Result<OutItem> {
        self.enqueue(OutBody::Text { text })
    }

    /// Copia `bytes` para dentro da fila (nunca só um caminho: a origem —
    /// PNG temporário do clipboard, caminho de um drop — pode sumir antes da
    /// entrega) e enfileira o Item de arquivo.
    pub fn enqueue_file(&self, name: String, bytes: &[u8]) -> io::Result<OutItem> {
        let mut inner = self.inner.lock().expect("fila de saída sem panic");
        let id = inner.manifest.next_id;
        std::fs::write(self.file_path(id), bytes)?;
        let item = OutItem {
            id,
            at_ms: now_ms(),
            body: OutBody::File {
                name,
                size: bytes.len() as u64,
            },
        };
        inner.manifest.next_id += 1;
        inner.manifest.items.push(item.clone());
        save_manifest(&self.dir, &inner.manifest)?;
        notify(&mut inner.listeners, &item);
        notify_change(&inner, &item, false);
        Ok(item)
    }

    /// Chamado a cada Item enfileirado ou confirmado, na thread que o gerou.
    pub fn on_change(&self, listener: impl Fn(&OutItem, bool) + Send + Sync + 'static) {
        self.inner
            .lock()
            .expect("fila de saída sem panic")
            .changed
            .push(Box::new(listener));
    }

    /// Cópia da fila de Pendentes, em ordem de chegada.
    pub fn pending(&self) -> Vec<OutItem> {
        self.inner
            .lock()
            .expect("fila de saída sem panic")
            .manifest
            .items
            .clone()
    }

    /// Bytes de um Item de arquivo ainda pendente, para a rota de download.
    /// `Err` carrega o `io::Error` original: quem chama distingue "não achou"
    /// (`ErrorKind::NotFound`) de um erro de leitura de verdade, que não pode
    /// virar 404 — o celular lê 404 como Item já resolvido, não como recusa.
    pub fn file_bytes(&self, id: u64) -> io::Result<Vec<u8>> {
        std::fs::read(self.file_path(id))
    }

    /// Remove um Item da fila de Pendentes — só a confirmação do celular faz
    /// isso, nunca a entrega em si, para reenvio depois de uma conexão
    /// cortada não duplicar o que já chegou.
    pub fn confirm(&self, id: u64) {
        let mut inner = self.inner.lock().expect("fila de saída sem panic");
        let Some(pos) = inner.manifest.items.iter().position(|i| i.id == id) else {
            return;
        };
        let item = inner.manifest.items.remove(pos);
        let _ = std::fs::remove_file(self.file_path(id));
        let _ = save_manifest(&self.dir, &inner.manifest);
        notify_change(&inner, &item, true);
    }

    /// Os Pendentes de agora, mais um canal para os que forem enfileirados
    /// depois — registrado sob o mesmo lock da leitura, para nenhum Item
    /// enfileirado entre o snapshot e a inscrição se perder.
    pub fn subscribe(&self) -> (Vec<OutItem>, UnboundedReceiver<OutItem>) {
        let mut inner = self.inner.lock().expect("fila de saída sem panic");
        let (tx, rx) = mpsc::unbounded_channel();
        inner.listeners.push(tx);
        (inner.manifest.items.clone(), rx)
    }
}
