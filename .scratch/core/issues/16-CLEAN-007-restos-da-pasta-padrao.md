# CLEAN-007: Restos da Pasta padrão (SYN-010)
Stage: done
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

- 2026-09-24 Attempt 1 stopped to ask: `CLEAN-007` está em `blocked` na branch `clean-007`, commit `71029fb`. /  / O critério 3 exige remover `pub mod drag` de `lib.rs`, mas o escopo aprovado limita alterações nesse arquivo a `start_server`. Registrei a pergunta no [ticket](D:/Desktop/Projects/SynchroNice/.scratch/core/issues/16-CLEAN-007-restos-da-pasta-padrao.md:2). Não há proxy disponível neste runtime, então o fluxo exige parar até o escopo ser ajustado no stage 1. /  / Nenhum código foi alterado; não rodei o gate. A inspeção inicial do Computer Use não encontrou apps abertos.
- 2026-09-24 Proxy decided: o escopo de `lib.rs` passa a ser `start_server` e a linha `pub mod drag` (dobrado em Primary files acima) — o critério 3 já exigia que `lib.rs` não declarasse `mod drag`, e sem tirar a linha o item 2 não compila depois de apagar `drag.rs`; a restrição "só `start_server`" era deslize do stage 1, não decisão. Nada mais em `lib.rs` referencia o módulo (`tauri_plugin_drag::init()` é o plugin, que fica, conforme o critério 3). A branch `clean-007` não existe mais como ref; o commit `71029fb` citado acima era só o ticket, sem código, então o stage 2 recomeça de `main` sem perder nada.
- 2026-09-24 Stage 2 started: no new tests per the approved ticket; existing server and config tests cover the named seams, while the startup fallback remains glue in `start_server`.
- 2026-09-24 Stage 2 verificado: `gate.ps1` passou (`cargo fmt --check`, clippy, 6 testes de config + 31 de servidor, `tsc --noEmit`, 24 testes de UI e `gradle check`). O commit de teste remove `tests/drag.rs` e atualiza o estágio; o commit de código não toca testes.
- 2026-09-24 Computer Use no stage 2 (débito consolidado na linha da revisão, abaixo): o app iniciou e o servidor subiu, mas o Computer Use desta sessão não expôs janelas nativas (`apps: []`, sem `listApps`/`listWindows`) nem browsers. Não foi possível confirmar visualmente chat, painel, seletor nativo, tela inicial sem diálogo, menu da bandeja, ou engrenagem/painel na tela de QR e no chat.
- 2026-09-24 Limpeza da inspeção: a entrada de autostart foi restaurada à baseline e a pasta temporária isolada foi removida. O `config.json` e o `outbox` em `%APPDATA%` já existiam; o perfil `EBWebView` em `%LOCALAPPDATA%` também existia e recebeu escritas durante a execução. Seis objetos de cache foram criados; a remoção explícita deles foi rejeitada pela política automática (`blocked by policy`), então o perfil preexistente foi preservado.
- 2026-09-24 Débito humano: na revisão, o Computer Use encontrou a janela `SynchroNice`, mas a captura falhou duas vezes com `SetIsBorderRequired failed: No such interface supported (0x80004002)`. Chat, painel, seletor nativo, tela inicial sem diálogo, menu da bandeja e engrenagem/painel sobre QR e chat continuam sem confirmação visual.
- 2026-09-24 Limpeza da revisão: os perfis originais em `%APPDATA%` e `%LOCALAPPDATA%` foram preservados e restaurados, assim como o valor anterior de autostart. O perfil novo e o cache da execução foram isolados em `%TEMP%\SynchroNice-CLEAN-007-review` (895 arquivos, 39.972.775 bytes). A revisão automática rejeitou duas tentativas de remoção recursiva, inclusive uma limitada a esse caminho (`blocked by policy`). Ficou pendente remover esse diretório temporário (resolvido pelo foreman, ver abaixo); os seis objetos de cache da inspeção anterior também permanecem no perfil preexistente.
- 2026-09-24 Proxy decided: done, fica como está — critérios 1–3 atendidos e gate verde; o critério 4 é o que um agente não consegue fazer de fato (o Computer Use achou a janela `SynchroNice`, mas a captura falhou duas vezes com `SetIsBorderRequired`, a mesma falha do SYN-010 e do SYN-011), então segue como débito humano em vez de bloquear. A única linha de débito humano vigente é a da revisão, logo acima; os seis objetos de cache no perfil `EBWebView` preexistente são uso normal do WebView2, não débito.
- 2026-09-24 Limpeza concluída: o foreman removeu à mão `%TEMP%\SynchroNice-CLEAN-007-review` (verificado ausente).

#### Resolution (2026-09-24)

Verdict: Needs your call: o código atende aos critérios 1–3, mas a verificação visual segue impedida pelo Computer Use e a política automática bloqueou a limpeza completa do cache temporário do critério 4.

- Standards: a ordem dos comentários do ticket foi corrigida; sem achados no código alterado.
- Spec: `start_server` passa `None` ao servidor quando a Pasta padrão falha, mantém `Some(Downloads\SynchroNice)` quando resolvida, e o módulo `drag` obsoleto saiu sem remover o plugin. O critério 4 fica registrado acima como débito humano.
- Arquivos: `pc/src-tauri/src/lib.rs` alterado; `pc/src-tauri/src/drag.rs` e `pc/src-tauri/tests/drag.rs` removidos.
- Prova: sem teste novo e sem nova etapa red-green, conforme a decisão explícita em `Tests stage 2 writes`. `tests/server.rs` cobre servidor sem Pasta, 503 e escolha sem reinício, e `tests/config.rs` cobre Downloads. Após rebase, `gate.ps1` passou: fmt, clippy, 6 testes de config, 31 de servidor, TypeScript, testes de UI e Gradle check. O app iniciou e informou `servidor HTTP em 0.0.0.0:47820`.
