# QA — 2026-10-02 — Emulador — SYN-019, revisão final

Código revisado: `983ebbf`, branch `syn-019`, já sobre a sessão
`sweatshop/2026-10-01-2019` em `d2c5759`. Recorte desta re-revisão:
o ❌ restante de callback concorrente e o delta desde `e9960c5`.
Nenhum código de produção ou teste foi alterado pelo stage 3.

`qa/up.ps1 -RunDir .scratch/qa-runs/2026-10-02-syn-019-final-review
-Sdk D:/AppData/Local/Android/Sdk` compilou APK e PC debug, pareou
`SynchroNice_API37` e confirmou `online:true`. `pc.ps1 presence`
confirmou HTTP 200. PC isolado: HTTP 47820, CDP 9222; emulador:
`127.0.0.1:5555`. O Par e a Pasta reais não foram usados.

Antes do upload, o kit registrou Pasta do PC vazia e cinco arquivos
`.trashed-*` anteriores no emulador. Nenhum arquivo foi enviado ao
emulador. O agente agiu como o celular por HTTP com o token do Par
isolado, e consultou a lista pelo comando Tauri real via `pc.ps1 invoke`.
Nenhum token consta deste relatório.

| Critério / caso | Veredito | Evidência |
| --- | --- | --- |
| 2 — recebido vai para a Lixeira | ✅ | Upload 201; exclusão 204; lista de recebidos de 1 para 0; arquivo ausente da Pasta. A entrada da Lixeira aponta para a Pasta isolada correta e tem SHA-256 idêntico. |
| 3 — repetição da exclusão | ✅ | Segundo `POST /items/delete` com o mesmo id respondeu 204; arquivo e Item permaneceram ausentes. |
| ❌ anterior — callback concorrente com Apagar tudo | ✅ | `observador_de_done_nao_trava_apagar_tudo_concorrente` passou no gate atual. Antes da correção, falhava por timeout do observador, conforme prova abaixo. |
| ❌ anterior — callback concorrente com finalização de upload | ✅ | `observador_nao_trava_a_finalizacao_de_upload_em_andamento` passou no gate atual. Antes da correção, falhava por timeout do observador. |

A Lixeira foi observada no executável debug atual pelo Shell do Windows,
incluindo a Pasta de origem e o conteúdo do arquivo. Os callbacks foram
verificados pelos testes na API pública/HTTP e pela revisão independente
do código; nenhum fluxo novo da janela foi exercitado (SYN-028).

Prova vermelho→verde conferida nos logs reais do stage 2, preservados
localmente como `stage2-red.log` e `stage2-green.log`: testes do commit
`a980c9d`, 61 HTTP verdes e dois vermelhos por timeout; correção de
`4e4c91f`, 63 HTTP verdes. Os commits de formatação `753f9b0` (testes)
e `7286d18` (produção) mantêm a separação teste/código.

Validação em primeiro plano:

- `powershell -ExecutionPolicy Bypass -File .\gate.ps1`: exit 0,
  `gate: passou`; fmt, Clippy e TypeScript verdes.
- Rust: 63 HTTP + 4 unitários + 7 config + 5 outbox = 79, sem falhas.
- UI: 47/47; kit QA: 14/14.
- Android: `check` verde; XMLs `UP-TO-DATE` com 71 testes do app e
  62 do cliente, zero falhas, erros e pulos.
- `assembleDebug` e `npx tauri build --debug --no-bundle` passaram no `up`.

Limitação dos testes concorrentes: usam pausas de 300 ms para agendamento;
o interleaving depende do escalonador. A prova vermelha observada
demonstra que os dois testes atingiram os ciclos reais. Sem bloqueador
ou verificação humana pendente neste recorte de backend.

Arquivo do critério 2:

- Origem: `D:/Desktop/Projects/SynchroNice/.scratch/qa-runs/2026-10-02-syn-019-final-review/data/Pasta/syn-019-final-57e9fe8cf2.bin`.
- Destino confirmado: `D:/$RECYCLE.BIN/S-1-5-21-2960696925-1232010053-3133060791-1001/$RQGBNHD.bin`.
- SHA-256 antes e na Lixeira: `50D88C59DA3F9A8F05D6AFC9C65F31C017B664ADB2959E8E07438274EBD35A27`.

Limpeza:

| path | destination |
| --- | --- |
| `D:/Desktop/Projects/SynchroNice/.scratch/qa-runs/2026-10-02-syn-019-final-review/data/Pasta/syn-019-final-57e9fe8cf2.bin` | `ja ausente` — já movido à Lixeira pelo backend testado; destino confirmado acima. |

`pc.ps1 clean` esvaziou o manifesto (`tracked:[]`); `phone.ps1 clean`
retornou `[]`. Os cinco arquivos anteriores do emulador foram preservados.
`qa/down.ps1` retornou `stopped:true, restored:false`; PC debug e emulador
encerrados. Nenhum PC release estava aberto antes do `up`.

Logs, prova JSON e script locais ignorados pelo git no subdiretório
homônimo: `gate.log`, `up.log`, `stage2-red.log`, `stage2-green.log`,
`probe-recycle.ps1`, `recycle-evidence.json`, `pc-cleanup.json`,
`phone-cleanup.json` e `down.log`. Só este Markdown é committed.

