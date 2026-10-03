//! A Sessão do PC: a lista de Itens em memória. Some quando o app encerra.

use serde::Serialize;
use std::collections::HashSet;
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

/// Estado de um Item de arquivo. Uma dimensão só, três valores: nasce
/// `Receiving`, termina em `Done` (renomeado para o nome final) ou `Failed`
/// (conexão caiu no meio do corpo, ou o rename falhou). Terminal nunca volta
/// a `Receiving`, e um dos dois terminais nunca sobrescreve o outro.
#[derive(Clone, Copy, Debug, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum FileStatus {
    Receiving,
    Done,
    Failed,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum ItemBody {
    Text {
        text: String,
    },
    File {
        name: String,
        size: u64,
        /// Bytes já gravados. Cresce a cada pedaço recebido. Não é o sinal de
        /// fim: com `size` desconhecido (0) nunca alcançaria `size`, e um
        /// arquivo de 0 byte já nasceria "completo" antes de sequer existir.
        received: u64,
        status: FileStatus,
    },
}

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct Item {
    pub id: u64,
    /// Id do celular (`X-Item-Id`), independente da chave local da bolha.
    #[serde(skip)]
    pub sender_id: Option<String>,
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
type DeleteListener = Arc<dyn Fn(&DeletedItems) + Send + Sync>;

/// Chaves locais que saíram da Sessão. As duas sequências podem colidir.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct DeletedItems {
    pub received: Vec<u64>,
    pub sent: Vec<u64>,
}

struct PendingDeletion {
    waiting: HashSet<u64>,
    items: DeletedItems,
}

#[derive(Default)]
struct Inner {
    next_id: u64,
    items: Vec<Item>,
    deleting: HashSet<u64>,
    listeners: Vec<Listener>,
    deletions: Vec<PendingDeletion>,
    deleted: Vec<DeleteListener>,
}

impl Inner {
    fn notify(&self, item: &Item) {
        for listener in &self.listeners {
            listener(item);
        }
    }

    fn finish_delete(&mut self, id: u64) -> bool {
        if !self.deleting.remove(&id) {
            return false;
        }
        self.items.retain(|item| item.id != id);
        for deletion in &mut self.deletions {
            deletion.waiting.remove(&id);
        }
        true
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
        self.push_text_with_sender_id(text, None)
    }

    pub fn push_text_with_sender_id(&self, text: String, sender_id: Option<String>) -> Item {
        let mut inner = self.inner.lock().expect("lista de Itens sem panic");
        inner.next_id += 1;
        let item = Item {
            id: inner.next_id,
            sender_id,
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
        self.start_file_with_sender_id(name, size, None)
    }

    pub fn start_file_with_sender_id(
        &self,
        name: String,
        size: u64,
        sender_id: Option<String>,
    ) -> Item {
        let mut inner = self.inner.lock().expect("lista de Itens sem panic");
        inner.next_id += 1;
        let item = Item {
            id: inner.next_id,
            sender_id,
            at_ms: now_ms(),
            body: ItemBody::File {
                name,
                size,
                received: 0,
                status: FileStatus::Receiving,
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
        if let ItemBody::File {
            received: r,
            status: FileStatus::Receiving,
            ..
        } = &mut slot.body
        {
            *r = received;
        } else {
            return;
        }
        let item = slot.clone();
        if !inner.deleting.contains(&id) {
            inner.notify(&item);
        }
    }

    /// Marca um Item de arquivo como terminado e avisa quem escuta. Chamado
    /// só depois do arquivo já estar no nome final na Pasta — nunca antes,
    /// para a UI não tentar ler um arquivo que ainda está com nome temporário.
    /// Sem efeito se o Item já chegou a um estado terminal (ver `file_failed`).
    pub fn file_done(&self, id: u64) {
        let mut inner = self.inner.lock().expect("lista de Itens sem panic");
        let Some(slot) = inner.items.iter_mut().find(|i| i.id == id) else {
            return;
        };
        match &mut slot.body {
            ItemBody::File { status, .. } if *status == FileStatus::Receiving => {
                *status = FileStatus::Done;
            }
            _ => return,
        }
        let item = slot.clone();
        if inner.finish_delete(id) {
            return;
        }
        inner.notify(&item);
    }

    /// Marca um Item de arquivo como morto (conexão caiu no meio do corpo, ou
    /// o rename para o nome final falhou) e avisa quem escuta. Sem efeito se
    /// o Item já chegou a um estado terminal — `file_done` e `file_failed`
    /// só agem sobre um Item ainda `Receiving`, para a limpeza de um envio
    /// cancelado e a tarefa de conclusão nunca disputarem o resultado.
    pub fn file_failed(&self, id: u64) {
        let mut inner = self.inner.lock().expect("lista de Itens sem panic");
        let Some(slot) = inner.items.iter_mut().find(|i| i.id == id) else {
            return;
        };
        match &mut slot.body {
            ItemBody::File { status, .. } if *status == FileStatus::Receiving => {
                *status = FileStatus::Failed;
            }
            _ => return,
        }
        let item = slot.clone();
        if inner.finish_delete(id) {
            return;
        }
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

    pub(crate) fn forget(&self, ids: &[u64], sent: Vec<u64>, notify: bool) {
        let mut inner = self.inner.lock().expect("lista de Itens sem panic");
        let requested: HashSet<u64> = ids.iter().copied().collect();
        let received: Vec<u64> = inner
            .items
            .iter()
            .filter(|item| requested.contains(&item.id) && !inner.deleting.contains(&item.id))
            .map(|item| item.id)
            .collect();
        let receiving: HashSet<u64> = inner
            .items
            .iter()
            .filter(|item| {
                requested.contains(&item.id)
                    && matches!(
                        item.body,
                        ItemBody::File {
                            status: FileStatus::Receiving,
                            ..
                        }
                    )
            })
            .map(|item| item.id)
            .collect();
        if notify && (!received.is_empty() || !sent.is_empty()) {
            // Repetir um pedido durante o upload não agenda outro aviso.
            let waiting = receiving
                .iter()
                .copied()
                .filter(|id| received.contains(id))
                .collect();
            inner.deletions.push(PendingDeletion {
                waiting,
                items: DeletedItems { received, sent },
            });
        }
        inner.deleting.extend(receiving);
        let deleting = inner.deleting.clone();
        inner
            .items
            .retain(|item| !requested.contains(&item.id) || deleting.contains(&item.id));
    }

    pub(crate) fn delete_requested(&self, id: u64) -> bool {
        self.inner
            .lock()
            .expect("lista de Itens sem panic")
            .deleting
            .contains(&id)
    }

    /// Chamado uma vez por exclusão recebida aplicada, depois de todos os
    /// uploads selecionados terminarem ou falharem. Nunca sob o lock da lista.
    pub fn on_delete(&self, listener: impl Fn(&DeletedItems) + Send + Sync + 'static) {
        self.inner
            .lock()
            .expect("lista de Itens sem panic")
            .deleted
            .push(Arc::new(listener));
    }

    pub(crate) fn notify_deleted(&self) {
        let (ready, listeners) = {
            let mut inner = self.inner.lock().expect("lista de Itens sem panic");
            let mut ready = Vec::new();
            for deletion in std::mem::take(&mut inner.deletions) {
                if deletion.waiting.is_empty() {
                    ready.push(deletion.items);
                } else {
                    inner.deletions.push(deletion);
                }
            }
            (ready, inner.deleted.clone())
        };
        for items in ready {
            for listener in &listeners {
                listener(&items);
            }
        }
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
