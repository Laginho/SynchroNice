# SYN-030: Opção "Iniciar com o Windows" na engrenagem do PC
Stage: reviewing
Status: ready-for-agent
Blocked by: none
Review: human
Difficulty: normal
Origin: triagem de CLEAN-018..025, 2026-10-03, pedido do humano

- Primary files:
  - pc/src-tauri/src/lib.rs (tirar o registro do `setup`; comandos `autostart_ligado` e `definir_autostart`)
  - pc/src-tauri/src/config.rs (quando o autostart pode ser lido e mudado)
  - pc/src-tauri/tests/config.rs
  - pc/index.html (checkbox no painel da engrenagem)
  - pc/ui/main.ts

#### What to build

Hoje o PC chama `autolaunch().enable()` em todo start fora do perfil isolado,
e o Windows sempre sobe o app no login. Isso passa a ser uma opção do usuário,
desligada por padrão (história 49 do spec).

- **Fonte da verdade.** O próprio HKCU Run, por meio do
  `tauri_plugin_autostart`. Nenhum campo novo no `config.json` nem em
  preferências. O checkbox mostra `is_enabled()`, e mudar o checkbox chama
  `enable()` ou `disable()`.
- **Nada no start.** O `setup` deixa de mexer no autostart. Numa instalação
  nova, o app não aparece no Run até o usuário marcar a opção. Numa instalação
  que já está no Run, nada muda: o checkbox aparece marcado.
- **Comandos.** `autostart_ligado` devolve o estado, e
  `definir_autostart(ligado)` muda. O plugin não ganha permissão na
  capability: a UI só fala com o autostart por esses dois comandos.
- **Perfil isolado** (`SYNCHRONICE_DATA_DIR` definido). Os dois comandos
  recusam com erro, sem ler nem gravar o Run, e o checkbox fica desabilitado.
  A recusa mora no Rust porque o kit chama comandos pelo CDP, e isso mantém a
  história 7 da spec do QA. O flag que o `resolve_data_dir` já devolve passa
  a querer dizer "pode mexer no autostart".
- **Build de debug.** `definir_autostart(true)` recusa com erro, para o Run
  nunca apontar para um exe de debug. Desligar e ler continuam valendo.
- **UI.** Um checkbox "Iniciar com o Windows", logo abaixo de "Copiar com
  pontuação", com `id="start-with-windows"`. Se a mudança falhar, o check
  volta ao estado anterior e o erro vai para o `console.error`, como em
  `mudar_pasta` e no Desparear.
- O comentário do `setup` sobre o registro idempotente sai junto com o código.

#### Acceptance criteria

1. O start do app, em release e fora do perfil isolado, não grava nem apaga
   a entrada do Run.
2. O painel da engrenagem tem o checkbox "Iniciar com o Windows" logo abaixo
   de "Copiar com pontuação", marcado se e só se o app está no Run.
3. Marcar o checkbox registra o exe em execução no Run, e desmarcar remove a
   entrada. O estado vale no próximo login e na próxima abertura do painel.
4. Se `definir_autostart` falhar, o checkbox volta ao estado anterior e o erro
   aparece no `console.error`.
5. No perfil isolado, `autostart_ligado` e `definir_autostart` devolvem erro
   sem tocar no Run, e o checkbox fica desabilitado.
6. Em build de debug, `definir_autostart(true)` devolve erro sem tocar no
   Run.
7. Uma função pura do `config.rs` diz se o autostart pode ser lido e mudado,
   a partir de "perfil isolado". Ela tem teste em `tests/config.rs`, no lugar
   do teste atual `diretorio_isolado_desliga_autostart_e_padrao_o_mantem`, que
   muda de nome se o sentido mudar.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Kit, no emulador:
- `pc.ps1 invoke -Isolated -Command autostart_ligado` devolve erro;
- o `#start-with-windows` está desabilitado no perfil isolado (critério 5).

Os critérios 1 a 4 e 6 mexem no HKCU Run real. O kit não os alcança sem
tocar no autostart do humano, então ficam como `Débito humano:`.

## Tests stage 2 writes (own commit, red)

Em `pc/src-tauri/tests/config.rs`: a função pura do critério 7. Com
`SYNCHRONICE_DATA_DIR`, o autostart não pode ser lido nem mudado; sem ela,
pode. O resto, que é o plugin, o registro e a UI, fica fora de teste
automático, como a spec já define para autostart e engrenagem.

## Comments

- Stage 2 (2026-10-03): seam confirmada pelo ticket: política pura em
  `config.rs`, exercitada por `tests/config.rs`. Chamadores examinados:
  `run`, `start_server` e `mudar_pasta`; os dois últimos usam só o caminho
  retornado por `resolve_data_dir`. O teste conserva esses caminhos e cobre
  perfil padrão, isolado e override definido mas vazio. Red: o gate
  `-TestsOnly` acusa E0425 porque `config::autostart_permitido` ainda não existe.

- Stage 2 concluído (2026-10-03): teste vermelho no commit `7f9eb57`;
  implementação em `12c6e0c`, formatação em `58beb5a`. O `setup` não registra
  autostart; os comandos conferem a permissão do perfil antes do plugin e
  recusam habilitar em debug. O checkbox consulta o plugin ao abrir o painel,
  desabilita durante operações e restaura o valor anterior se a mudança falhar.
  Nenhuma capability nem preferência persistente foi adicionada.
  Gate completo passou com `RUST_TEST_THREADS=1`: rustfmt, Clippy, TypeScript,
  83 testes Rust, 61 da UI, 14 do QA e `gradle check` (166 testes Android nos
  relatórios, zero falhas/erros; tarefas de teste em cache na rodada final).
  Tentativas anteriores: rede do Gradle negada no sandbox; ajuste de rustfmt;
  timeout PowerShell no teste QA de wifi-off; timeout de 5 s no teste Rust
  `dois_envios_concorrentes_do_mesmo_nome_preservam_ambos`. Ambos passaram
  depois, sem mudar testes; a execução sequencial dos casos Rust conserva os
  uploads concorrentes dentro do teste. Logs locais em
  `.scratch/qa-runs/SYN-030-stage2/`. Diff conferido: apenas Primary files e
  este ticket, com testes separados dos commits de implementação.
  Pendente para stage 3: kit no perfil isolado (critério 5) e verificações
  manuais dos critérios 1–4 e 6, conforme a seção Verification; o autostart
  real da máquina não foi exercitado nesta implementação.

- Triagem 2026-10-03 (/grill-proxy, com o humano):
  > *This was generated by AI during triage.*
  Pedido do humano durante a triagem dos CLEAN do kit. As decisões:
  - o Run é a fonte da verdade (Q11, humano);
  - a instalação existente fica como está (Q12, proxy);
  - o isolado recusa no Rust (Q13, proxy);
  - os docs mudam sem ADR (Q14, proxy);
  - a UI é um checkbox com o erro no `console.error` (Q15, proxy);
  - a guarda de debug e a fatia separada do CLEAN-024 (Q10, humano).

  `Review: human` porque o ticket mexe no autostart da máquina do humano.

- Stage 3 (2026-10-03): eixos Standards e Spec concluídos. Nenhuma falha de
  critério ou regressão no código; separação test-first conferida nos quatro
  commits. Correção pequena de documentação: os roteiros de
  `docs/pr-3-review-follow-up.md` e do walkthrough de 2026-09-27 deixam de
  dizer que abrir o app registra autostart; as specs core e QA descrevem a
  opção e a permissão de acesso atuais. Nenhum código ou teste foi alterado.
  Validação pelo kit e gate ainda em curso.
