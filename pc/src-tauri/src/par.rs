//! O Par: nome de instância e token, vivos em memória e espelhados na
//! configuração (SYN-005). Uma alça compartilhada entre o servidor HTTP e a
//! janela — rotacionar aqui já vale para a próxima requisição, sem
//! reiniciar o servidor.

use crate::config;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

#[derive(Clone)]
pub struct Par {
    config_dir: PathBuf,
    nome_instancia: String,
    token: Arc<Mutex<String>>,
}

impl Par {
    /// Carrega o Par gravado em `config_dir`, ou cria um novo (nome de
    /// instância e token) na primeira vez.
    pub fn load_or_create(config_dir: &Path) -> io::Result<Self> {
        let nome_instancia = config::load_or_create_nome_instancia(config_dir)?;
        let token = config::load_or_create_token(config_dir)?;
        Ok(Self {
            config_dir: config_dir.to_path_buf(),
            nome_instancia,
            token: Arc::new(Mutex::new(token)),
        })
    }

    pub fn nome_instancia(&self) -> &str {
        &self.nome_instancia
    }

    pub fn token(&self) -> String {
        self.token.lock().expect("Par sem panic").clone()
    }

    /// Gera um token novo, grava na configuração e passa a valer
    /// imediatamente — o anterior é recusado a partir da próxima requisição
    /// que qualquer alça deste Par (inclusive a que autentica o servidor)
    /// verificar.
    pub fn rotate(&self) -> io::Result<String> {
        let novo = config::new_token()?;
        config::save_token(&self.config_dir, &novo)?;
        *self.token.lock().expect("Par sem panic") = novo.clone();
        Ok(novo)
    }

    /// A célula de token compartilhada — o servidor autentica contra o
    /// valor atual dela, mesmo depois de uma rotação.
    pub(crate) fn shared_token(&self) -> Arc<Mutex<String>> {
        self.token.clone()
    }
}
