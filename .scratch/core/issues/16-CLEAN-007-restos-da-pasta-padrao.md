# CLEAN-007: Restos da Pasta padrão (SYN-010)
Stage: implementing
Status: ready-for-agent
Blocked by: none
Review: agent

- Primary files:
  - pc/src-tauri/src/lib.rs (só `start_server` e a linha `pub mod drag`, que sai)
  - pc/src-tauri/src/drag.rs (sai)
  - pc/src-tauri/tests/drag.rs (sai)

#### What to build

Dois restos achados na revisão do PR #4 (SYN-010).

1. **Servidor não sobe sem Downloads.** Em `start_server`, quando
   `pasta_efetiva` devolve erro (nenhuma Pasta gravada *e*
   `download_dir()` falha), o código faz `return`: não sobem o servidor
   HTTP, a Outbox e o anúncio. O texto para de funcionar e "Mudar pasta…"
   falha com "servidor HTTP não está disponível", então o usuário fica sem
   saída pela UI. Antes do SYN-010 o servidor subia com `pasta: None`: a rota
   de arquivo devolvia 503 e o texto funcionava. `server::spawn` continua
   aceitando `Option<PathBuf>`, e `Running::escolher_pasta` já cobre a
   passagem de `None` para `Some` com o servidor no ar. A correção é
   registrar o erro e subir com `None` em vez de sair.

2. **`drag::resolve` morto.** Desde o SYN-010, `caminho_para_arrastar`
   resolve pelo id do Item (`Running::caminho_do_arquivo`). Fora de
   `tests/drag.rs`, nada mais chama `drag::resolve`. Tire o módulo e o
   teste dele, se nada mais depender do módulo.

#### Acceptance criteria

1. Sem Pasta gravada e sem Downloads resolvível, o servidor sobe mesmo assim:
   o texto funciona, a rota de arquivo recusa, e "Mudar pasta…" grava a Pasta
   e libera o próximo arquivo sem reiniciar.
2. Com Downloads resolvível, o comportamento não muda: sem Pasta gravada, o
   destino é `Downloads\SynchroNice`.
3. `pc/src-tauri/src/drag.rs` e `pc/src-tauri/tests/drag.rs` não existem
   mais, e `lib.rs` não declara `mod drag`. O plugin `tauri_plugin_drag`
   continua registrado.
4. O débito visual do SYN-010 é refeito com o Computer Use, com o app
   rodando: chat, painel, seletor nativo, tela inicial sem diálogo, menu da
   bandeja, e a engrenagem e o painel sobre a tela de QR e no chat. Cada item
   fica confirmado ou vira achado em `## Comments` deste ticket; só o que o
   Computer Use não conseguir ver volta a ser `Débito humano:`. Limpe no fim o
   que a inspeção criar (perfil, autostart, pastas em `%APPDATA%`,
   `%LOCALAPPDATA%` e `%TEMP%`).

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

Nenhum. O item 1 é cola em `lib.rs`, que depende de um `AppHandle` e não tem
costura de teste. O lado do servidor já está coberto por
`escolher_pasta_com_servidor_no_ar_libera_upload_sem_reiniciar`
(`tests/server.rs`), e a Pasta padrão, pelos testes de `tests/config.rs`. O
item 2 é deleção. Diga isso no commit.

## Comments

- 2026-09-24 Stage 2 started: no new tests per the approved ticket; existing server and config tests cover the named seams, while the startup fallback remains glue in `start_server`.

- 2026-09-24 Attempt 1 stopped to ask: `CLEAN-007` está em `blocked` na branch `clean-007`, commit `71029fb`. /  / O critério 3 exige remover `pub mod drag` de `lib.rs`, mas o escopo aprovado limita alterações nesse arquivo a `start_server`. Registrei a pergunta no [ticket](D:/Desktop/Projects/SynchroNice/.scratch/core/issues/16-CLEAN-007-restos-da-pasta-padrao.md:2). Não há proxy disponível neste runtime, então o fluxo exige parar até o escopo ser ajustado no stage 1. /  / Nenhum código foi alterado; não rodei o gate. A inspeção inicial do Computer Use não encontrou apps abertos.

- 2026-09-24 Proxy decided: o escopo de `lib.rs` passa a ser `start_server` e a linha `pub mod drag` (dobrado em Primary files acima) — o critério 3 já exigia que `lib.rs` não declarasse `mod drag`, e sem tirar a linha o item 2 não compila depois de apagar `drag.rs`; a restrição "só `start_server`" era deslize do stage 1, não decisão. Nada mais em `lib.rs` referencia o módulo (`tauri_plugin_drag::init()` é o plugin, que fica, conforme o critério 3). A branch `clean-007` não existe mais como ref; o commit `71029fb` citado acima era só o ticket, sem código, então o stage 2 recomeça de `main` sem perder nada.
