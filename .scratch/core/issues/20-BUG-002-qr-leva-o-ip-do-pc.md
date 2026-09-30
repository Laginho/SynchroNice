# BUG-002: Sem mDNS entre cabo e Wi-Fi, o celular pareado nunca acha o PC
Stage: to-implement
Status: ready-for-agent
Blocked by: none
Review: agent

- Primary files:
  - pc/src-tauri/src/qr.rs (campo novo no QR)
  - pc/src-tauri/src/lib.rs (passa o IP para `qr::par_svg`)
  - pc/src-tauri/src/server.rs (doc do contrato do QR, cabeçalho)
  - android/protocol-client/src/main/kotlin/synchronice/protocolclient/PairingInfo.kt
  - android/app/src/main/java/synchronice/android/MainActivity.kt (`AppState.acceptQr`; nada mais)
  - testes: `PairingInfoTest.kt`, teste do `qr.rs`

#### What to build

Achado do walkthrough de QA (2026-09-30, passo B.3). O PC está no cabo
(192.168.18.12, rede Privada) e o celular no Wi-Fi do mesmo roteador. O celular
lê o QR, abre o chat, e nada sincroniza. O PC fica na tela de QR até ser
reiniciado.

Diagnóstico confirmado: no navegador do celular,
`http://192.168.18.12:47820/listen` responde 401. A conexão direta funciona, e
o firewall e o servidor estão certos. O que falha é a descoberta: o QR não leva
IP (`server.rs`, contrato do SYN-005), o celular só acha o PC por mDNS
(`NsdInstanceResolver`, com timeout de 2 s) e, no primeiro uso, não tem
`last-address` para cair. O roteador não repassa o multicast entre o cabo e o
Wi-Fi, ou a descoberta não termina em 2 s. Nos dois casos, `currentAddress()`
lança "PC não encontrado" para sempre.

O PC preso no QR é consequência, não um segundo bug: a janela só troca para o
chat quando chega a primeira presença (`main.ts`, listener de `presenca`), e a
presença nunca chega. Reiniciar abre no chat porque o Par já existe, mas também
não conecta.

Correção: **o QR leva o IP do PC como semente do `last-address`.**

- PC: campo novo `"ip":"<IPv4>"` no JSON do QR. O IP é o de saída para a LAN,
  obtido com o truque do `UdpSocket` (bind `0.0.0.0:0`, `connect` num IP
  público qualquer, sem mandar nada, e ler `local_addr()`), só com a biblioteca
  padrão. Se falhar, o campo é omitido. ponytail: um IP só, o da rota padrão. Se
  um PC com várias placas escolher a errada, vira uma lista.
- Android: `parsePairingQr` lê `ip` como opcional (um IPv4 válido ou ausente;
  inválido é ignorado, não rejeita o Par). `acceptQr` grava esse IP em
  `last-address` junto com o Par. O mDNS continua na frente: se resolver, ele
  sobrescreve, como já faz hoje. O IP do QR só cobre a falta dele.
- Contrato: atualizar o bloco "QR mostrado pelo PC" no cabeçalho de
  `server.rs`. Um QR sem `ip` (formato antigo) continua válido.

#### Acceptance criteria

1. `parsePairingQr` aceita o QR com `ip` e o expõe; aceita o QR sem `ip`
   (formato antigo), com o IP nulo; um `ip` malformado não derruba o Par.
2. Depois de `acceptQr` com `ip`, `knownAddress.get()` devolve esse IP, mesmo
   com um resolvedor que sempre devolve `null`.
3. `qr::par_svg` gera um JSON com `ip` quando recebe um. Teste de unidade sobre
   o JSON, antes de virar SVG, se precisar expor a serialização.
4. Os testes existentes seguem verdes.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Débito humano previsto: repetir o passo B.3 do walkthrough com o PC no cabo.

## Comments
