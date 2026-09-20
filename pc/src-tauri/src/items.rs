//! A Sessão do PC: a lista de Itens em memória. Some quando o app encerra.

use serde::Serialize;
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct Item {
    pub id: u64,
    pub text: String,
    /// Hora de chegada, em milissegundos desde a época Unix.
    pub at_ms: u64,
}

type Listener = Box<dyn Fn(&Item) + Send + Sync>;

#[derive(Default)]
struct Inner {
    items: Vec<Item>,
    listeners: Vec<Listener>,
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
            text,
            at_ms: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0),
        };
        inner.items.push(item.clone());
        for listener in &inner.listeners {
            listener(&item);
        }
        item
    }

    /// Cópia da lista, em ordem de chegada.
    pub fn snapshot(&self) -> Vec<Item> {
        self.inner
            .lock()
            .expect("lista de Itens sem panic")
            .items
            .clone()
    }

    /// Chamado a cada Item novo, na thread que o criou.
    pub fn on_new(&self, listener: impl Fn(&Item) + Send + Sync + 'static) {
        self.inner
            .lock()
            .expect("lista de Itens sem panic")
            .listeners
            .push(Box::new(listener));
    }
}
