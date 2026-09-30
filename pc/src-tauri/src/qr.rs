//! Renderiza o QR de pareamento (SYN-005): nome de instância, porta e
//! token, como JSON — o celular decodifica os três campos para achar e
//! autenticar no PC.

use qrcode::render::svg;
use qrcode::QrCode;
use serde::Serialize;
use std::net::Ipv4Addr;

#[derive(Serialize)]
struct Conteudo<'a> {
    instance_name: &'a str,
    port: u16,
    token: &'a str,
}

/// JSON do QR de pareamento (o que o celular decodifica).
pub fn par_json(nome_instancia: &str, port: u16, token: &str, _ip: Option<Ipv4Addr>) -> String {
    let conteudo = Conteudo {
        instance_name: nome_instancia,
        port,
        token,
    };
    serde_json::to_string(&conteudo).expect("conteúdo do QR serializa")
}

/// SVG do QR de pareamento, para a tela de "sem Par" e para "parear de novo".
pub fn par_svg(nome_instancia: &str, port: u16, token: &str, ip: Option<Ipv4Addr>) -> String {
    let json = par_json(nome_instancia, port, token, ip);
    let code = QrCode::new(json.as_bytes()).expect("conteúdo do Par sempre cabe no QR");
    code.render()
        .min_dimensions(240, 240)
        .dark_color(svg::Color("#1b1b1b"))
        .light_color(svg::Color("#faedc8"))
        .build()
}

#[cfg(test)]
mod tests {
    use super::*;

    const TOKEN: &str = "0123456789abcdef0123456789abcdef";

    #[test]
    fn json_leva_o_ip_quando_recebe_um() {
        let json = par_json(
            "synchronice-12345678",
            47820,
            TOKEN,
            Some(Ipv4Addr::new(192, 168, 18, 12)),
        );
        let valor: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(valor["ip"], "192.168.18.12");
        assert_eq!(valor["instance_name"], "synchronice-12345678");
        assert_eq!(valor["port"], 47820);
        assert_eq!(valor["token"], TOKEN);
    }

    #[test]
    fn json_omite_o_ip_quando_nao_ha() {
        let json = par_json("synchronice-12345678", 47820, TOKEN, None);
        let valor: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert!(valor.get("ip").is_none());
    }
}
