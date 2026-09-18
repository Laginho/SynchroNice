# SYN-001: Casca do PC
Stage: done
Status: ready-for-agent
Blocked by: none
Review: human

- Primary files:
  - New: gate.ps1
  - New: .gitignore
  - New: pc/** (projeto Tauri: `pc/src-tauri/` Rust, `pc/ui/` HTML/TS sem framework)
  - docs/DECISIONS.md (só para registrar o gate)

#### What to build

O app do PC existe e vive na bandeja. Ao abrir pela primeira vez, pede a Pasta
com o diálogo nativo e grava a escolha. Mostra o chat vazio (variante A do
protótipo: fundo escuro, bolhas claras, composer embaixo, texto de estado vazio
dizendo o que fazer). Fechar a janela só esconde; a bandeja tem "Abrir" e
"Sair". Sair encerra de fato. O app se registra para abrir com o Windows.
Nada de rede ainda.

Também nasce aqui o gate do repositório: um script que roda typecheck, lint e
testes de tudo que existir em `pc/` e, quando houver, `android/`.

#### Acceptance criteria

1. `cargo tauri dev` abre uma janela com o chat vazio e a mensagem de estado vazio.
2. No primeiro uso, um diálogo nativo pede a Pasta; a escolha sobrevive a reabrir o app; não pergunta de novo.
3. Fechar a janela (X) esconde para a bandeja; o processo continua vivo.
4. O ícone da bandeja tem "Abrir" (mostra a janela) e "Sair" (encerra o processo).
5. O app fica registrado no autostart do usuário do Windows após o primeiro uso.
6. O executável de release tem menos de 20 MB e o processo ocioso usa menos de 80 MB de RAM.
7. `gate.ps1` roda `cargo fmt --check`, `cargo clippy`, `cargo test` em `pc/src-tauri` e `tsc --noEmit` em `pc/ui`, e sai com código diferente de zero se qualquer um falhar.
8. A leitura e gravação da configuração (caminho da Pasta) é coberta por teste.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 1 a 6: manual, descrever o que foi visto no Resolution.

## Tests stage 2 writes (own commit, red)

- `pc/src-tauri/tests/config.rs`: gravar e reler o caminho da Pasta em um diretório temporário; ausência de config devolve "não escolhida". Vermelho porque o módulo de configuração não existe.

## Comments

- 2026-09-17 (stage 2): critérios 1–6 são manuais e dependem de uma janela
  nativa (diálogo de Pasta, bandeja, autostart) que esta sessão não tem como
  observar — sem ferramenta de automação de GUI nativa disponível, e rodar
  `cargo tauri dev` dispara de fato o diálogo nativo na tela. Não tentei
  rodar, para não abrir uma janela sem avisar. `cargo build`, `cargo test` e
  o gate completo passam (critérios 7 e 8, verificados). Falta ao humano
  rodar `cargo tauri dev` e descrever o que viu para 1–6.

#### Resolution (2026-09-17)

Verdict: Needs your call: critérios 1 a 6 são manuais e ninguém os observou
ainda, e a revisão mexeu justamente no fluxo de primeiro uso e no autostart —
os dois trechos que só aparecem numa janela real.

**Corrigido nesta revisão** (commit `6783b4d`, tudo dentro dos Primary files,
nenhum teste novo, portanto small fix e não reabertura):

1. `pc/src-tauri/src/lib.rs` — `blocking_pick_folder` era chamado na `setup`,
   que roda na thread principal. `tauri-plugin-dialog` documenta na própria
   assinatura: "should *NOT* be used when running on the main thread", e para a
   versão com callback "should be used when running on the main thread to avoid
   deadlocks with the event loop". Se travasse, os critérios 1 a 4 caíam juntos.
   Agora usa `pick_folder` com callback.
2. `pc/src-tauri/src/lib.rs` — critério 5 estava furado: `autolaunch().enable()`
   vivia dentro do ramo de sucesso do diálogo, e a função retorna cedo quando já
   existe Pasta gravada. Resultado: quem já tinha configuração nunca se
   registrava no autostart. Subiu para a `setup`, sem condição (é idempotente).
3. `pc/src-tauri/src/lib.rs` — três panics na inicialização (`app_config_dir`,
   `into_path`, `save_pasta`) com `panic = "abort"` no perfil de release: o app
   morria sem console e sem janela. Agora degradam com mensagem no stderr.
4. `gate.ps1` — falso verde. `Test-Path "pc/src-tauri"` resolvia contra o cwd,
   não contra o script. Rodado de qualquer outro diretório, os três guardas
   erravam, `$failed` ficava falso e o gate imprimia `gate: passou` com exit 0,
   sem rodar nada. Ancorado em `$PSScriptRoot`. Prova, com o `gate.ps1` como
   estava em `7da2137`, rodado de `C:\Users\bruno`:

       gate: passou
       exit=0

   E o corrigido, do mesmo diretório, roda os quatro passos e sai 0.
5. `gate.ps1` — `./gradlew check` não executa no Windows PowerShell;
   `.\gradlew.bat`. Passo dormente até `android/` existir (SYN-007), não
   verificado.
6. `pc/src-tauri/tauri.conf.json` — `productName` e o título da janela eram
   ambos `"pc"`. O critério 1 inspeciona essa janela. Metadados de scaffold do
   `Cargo.toml` (`"A Tauri App"`, `authors = ["you"]`) junto.
7. `pc/index.html` + `pc/ui/styles.css` — a variante A tem no composer do PC a
   dica `Enter envia · arraste ou Ctrl+V` (`prototype/chat-prototype.html:199`).
   Faltava, com a regra `.composer .hint` também ausente do CSS.

**Duas escolhas que quero que você olhe:**

- Autostart agora é habilitado em toda abertura, inclusive antes de o usuário
  escolher a Pasta e inclusive se ele cancelar o diálogo. É a leitura robusta de
  "após o primeiro uso" e evita o furo do item 2, mas registra o app mesmo para
  quem desistiu. Se preferir só depois da escolha, é mover uma linha de volta
  para dentro do callback. Nota prática: `cargo tauri dev` registra o binário de
  `target/debug` no autostart do seu usuário; para desfazer, apague o valor
  `SynchroNice` em `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`.
- O `input` e o botão do composer ficaram `disabled`. A variante A não tem isso,
  mas não há rede nenhuma até SYN-004, e um Enter que não faz nada é pior que um
  campo visivelmente inativo. Se quiser fidelidade ao protótipo, tira os dois
  atributos.

**Aceito como está, sem mexer:**

- `load_pasta` mapeia ausente, ilegível e JSON corrompido todos para "não
  escolhida", então uma configuração corrompida faz o app perguntar de novo e
  sobrescrever. É auto-cura, e o critério 8 cobre o caminho normal.
- `tauri-plugin-opener` entrou pelo scaffold e não é usado (`Cargo.toml`,
  `lib.rs`, `package.json`, `capabilities/default.json`). Peso morto, mas tirar
  mexe em quatro arquivos por nada agora.
- `pc/ui/styles.css` já tem as regras `.item` das bolhas, comentadas como "a
  partir de SYN-002". Scaffolding para depois; SYN-002 usa.
- `pc/index.html` está em `pc/`, não em `pc/ui/` como diz o `## Layout` do
  `AGENTS.md`. É a convenção do Vite (o `index.html` é o entry na raiz do
  projeto); mexer nisso é configurar `root` no Vite. Ou ajuste a linha do Layout.
- O ticket declara Primary files `gate.ps1`, `.gitignore`, `pc/**` e
  `docs/DECISIONS.md`. Fora deles só foi tocado o próprio arquivo do ticket, que
  é a escrituração do `ticket-flow`.

**Prova red-green.** Vermelho: em `a1e86f1`, `pc/src-tauri/src/` continha só
`lib.rs` e `main.rs` (`git ls-tree a1e86f1 pc/src-tauri/src/`), e aquele `lib.rs`
não tinha `mod config` — `use pc_lib::config;` em `tests/config.rs` não
compilava. Vermelho pelo motivo que o ticket previu: "o módulo de configuração
não existe". Verde: os 2 testes passam. Separação mecânica confirmada — o commit
de código `af440b0` não toca nenhum arquivo de teste.

**Gate** (`powershell -ExecutionPolicy Bypass -File .\gate.ps1`, depois das
correções):

    ==> cargo fmt --check (pc)
    ==> cargo clippy (pc)
    ==> cargo test (pc)
    test result: ok. 2 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out
    (mais 3 binários de teste com 0 testes)
    ==> tsc --noEmit (pc/ui)
    gate: passou

Critérios 7 e 8: verificados. Critérios 1 a 6: seus. Rode `cargo tauri dev` e
confira janela, diálogo da Pasta, X escondendo na bandeja, "Abrir"/"Sair", e o
autostart; o critério 6 (exe < 20 MB, ocioso < 80 MB) precisa de
`cargo tauri build`.

**Fechamento (2026-09-17).** Bruno rodou `npx tauri dev` e confirmou os
critérios 1 a 5 (janela com estado vazio, diálogo da Pasta só na primeira
abertura, X escondendo na bandeja, "Abrir"/"Sair", autostart registrado).
Critério 6: `npx tauri build --no-bundle` gerou `pc.exe` com 4,4 MB
(4.584.448 bytes). Autostart incondicional e composer `disabled` aceitos como
estão. PR #1 mergeado em `main` (`2787fcd`).
