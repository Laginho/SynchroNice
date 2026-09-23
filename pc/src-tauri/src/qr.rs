//! Renderiza o QR de pareamento (SYN-005): nome de instância, porta e
//! token, como JSON — o celular decodifica os três campos para achar e
//! autenticar no PC.

use qrcode::render::svg;
use qrcode::QrCode;
use serde::Serialize;

#[derive(Serialize)]
struct Conteudo<'a> {
    instance_name: &'a str,
    port: u16,
    token: &'a str,
}

/// SVG do QR de pareamento, para a tela de "sem Par" e para "parear de novo".
pub fn par_svg(nome_instancia: &str, port: u16, token: &str) -> String {
    let conteudo = Conteudo {
        instance_name: nome_instancia,
        port,
        token,
    };
    let json = serde_json::to_string(&conteudo).expect("conteúdo do QR serializa");
    let code = QrCode::new(json.as_bytes()).expect("conteúdo do Par sempre cabe no QR");
    code.render()
        .min_dimensions(240, 240)
        .dark_color(svg::Color("#1b1b1b"))
        .light_color(svg::Color("#faedc8"))
        .build()
}
