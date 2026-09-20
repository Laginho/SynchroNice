//! A Sessão do PC: a lista de Itens em memória. Some quando o app encerra.

use serde::Serialize;
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum ItemBody {
    Text {
        text: String,
    },
    File {
        name: String,
        size: u64,
        /// Bytes já gravados. Cresce a cada pedaço recebido; chega a `size`
        /// quando o arquivo terminou de chegar.
        received: u64,
    },
}

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct Item {
    pub id: u64,
    /// Hora de chegada, em milissegundos desde a época Unix.
    pub at_ms: u64,
    #[serde(flatten)]
    pub body: ItemBody,
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

type Listener = Box<dyn Fn(&Item) + Send + Sync>;

#[derive(Default)]
struct Inner {
    items: Vec<Item>,
    listeners: Vec<Listener>,
}

impl Inner {
    fn notify(&self, item: &Item) {
        for listener in &self.listeners {
            listener(item);
        }
    }
}

/// Lista compartilhada entre o servidor e a janela. Clonar dá outra alça para
/// a mesma lista.
#[derive(Clone, Default)]
pub struct Items {
    inner: Arc<Mutex<Inner>>,
}

impl Items {
    pub fn new() -> Self {
        Self::default()
    }

    /// Acrescenta um Item de texto ao fim da lista e avisa quem escuta.
    pub fn push_text(&self, text: String) -> Item {
        let mut inner = self.inner.lock().expect("lista de Itens sem panic");
        let item = Item {
            id: inner.items.len() as u64 + 1,
            at_ms: now_ms(),
            body: ItemBody::Text { text },
        };
        inner.items.push(item.clone());
        inner.notify(&item);
        item
    }

    /// Acrescenta um Item de arquivo com `received: 0` — a bolha nasce antes
    /// dos bytes chegarem, para mostrar a barra de progresso desde o início.
    pub fn start_file(&self, name: String, size: u64) -> Item {
        let mut inner = self.inner.lock().expect("lista de Itens sem panic");
        let item = Item {
            id: inner.items.len() as u64 + 1,
            at_ms: now_ms(),
            body: ItemBody::File {
                name,
                size,
                received: 0,
            },
        };
        inner.items.push(item.clone());
        inner.notify(&item);
        item
    }

    /// Atualiza os bytes recebidos de um Item de arquivo e avisa quem escuta
    /// de novo, com o mesmo id. Sem efeito se o id não existir ou não for
    /// mais um arquivo em andamento.
    pub fn file_progress(&self, id: u64, received: u64) {
        let mut inner = self.inner.lock().expect("lista de Itens sem panic");
        let Some(slot) = inner.items.iter_mut().find(|i| i.id == id) else {
            return;
        };
        if let ItemBody::File { received: r, .. } = &mut slot.body {
            *r = received;
        }
        let item = slot.clone();
        inner.notify(&item);
    }

    /// Cópia da lista, em ordem de chegada.
    pub fn snapshot(&self) -> Vec<Item> {
        self.inner
            .lock()
            .expect("lista de Itens sem panic")
            .items
            .clone()
    }

    /// Chamado a cada Item novo ou atualizado, na thread que o gerou.
    pub fn on_new(&self, listener: impl Fn(&Item) + Send + Sync + 'static) {
        self.inner
            .lock()
            .expect("lista de Itens sem panic")
            .listeners
            .push(Box::new(listener));
    }
}
