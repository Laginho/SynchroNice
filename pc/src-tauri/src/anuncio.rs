//! Anúncio do serviço na rede local por mDNS (SYN-005), para o celular achar
//! o IP atual do PC pelo nome de instância do Par, sem digitar nada.

use mdns_sd::{ServiceDaemon, ServiceInfo};
use std::collections::HashMap;
use std::io;

const TIPO_SERVICO: &str = "_synchronice._tcp.local.";

/// O anúncio no ar. Desanuncia (goodbye packet) e desliga o daemon quando
/// solto — ao sair pela bandeja ou trocar de anúncio numa rotação futura.
pub struct Anuncio {
    daemon: ServiceDaemon,
    fullname: String,
}

impl Anuncio {
    /// Sobe o daemon de mDNS e anuncia `nome_instancia` em `porta`, com os
    /// endereços IP do host detectados automaticamente.
    pub fn subir(nome_instancia: &str, porta: u16) -> io::Result<Self> {
        let daemon = ServiceDaemon::new().map_err(io::Error::other)?;
        let hostname = format!("{nome_instancia}.local.");
        let info = ServiceInfo::new(
            TIPO_SERVICO,
            nome_instancia,
            &hostname,
            "",
            porta,
            None::<HashMap<String, String>>,
        )
        .map_err(io::Error::other)?
        .enable_addr_auto();
        let fullname = info.get_fullname().to_string();
        daemon.register(info).map_err(io::Error::other)?;
        Ok(Self { daemon, fullname })
    }
}

impl Drop for Anuncio {
    fn drop(&mut self) {
        let _ = self.daemon.unregister(&self.fullname);
    }
}
