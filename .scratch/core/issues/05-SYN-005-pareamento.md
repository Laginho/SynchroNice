# SYN-005: Pareamento por QR e anúncio na rede
Stage: done
Status: ready-for-agent
Blocked by: SYN-002
Review: agent

- Primary files:
  - pc/src-tauri/src/** (geração do Par, rotação de token, anúncio mDNS)
  - pc/src-tauri/tests/server.rs
  - pc/src-tauri/Cargo.toml (mDNS, QR)
  - pc/ui/** (tela de QR, ação "parear de novo")

#### What to build

O PC mostra um QR quando não há Par, e sob demanda em "parear de novo". O QR
carrega o nome de instância do serviço, a porta e o token. O PC anuncia o
serviço por mDNS na rede local com esse nome de instância, para o celular
achar o IP atual sem digitar nada. Parear de novo gera token novo: o anterior
passa a ser recusado imediatamente. O Par (nome de instância e token) é
gravado na configuração e sobrevive a reiniciar.

#### Acceptance criteria

1. Sem Par gravado, a janela mostra um QR em vez do chat vazio; o conteúdo decodificado do QR contém nome de instância, porta e token.
2. Um leitor de mDNS na mesma rede (por exemplo `dns-sd -B` ou app de descoberta) vê o serviço com o nome de instância do QR.
3. "Parear de novo" mostra um QR com token diferente; requisições com o token antigo passam a receber recusa.
4. Reiniciar o app mantém o mesmo nome de instância e token.
5. O nome de instância é estável por instalação e não contém informação pessoal (nem nome do usuário do Windows).

#### Verification

    dns-sd -B _synchronice._tcp
    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 1, 2 e 3 (parte visual): manual.

## Tests stage 2 writes (own commit, red)

- `pc/src-tauri/tests/server.rs`: criar Par gera token e nome de instância e os grava; recriar a partir da configuração devolve os mesmos; rotacionar troca o token e o servidor recusa o antigo e aceita o novo. Vermelho porque o módulo de Par não existe.

## Comments

- 2026-09-20 (revisão de SYN-002): **o servidor pode não subir e a janela não
  conta.** `start_server` (`pc/src-tauri/src/lib.rs`) só faz `eprintln!` e
  volta quando o token falha ou quando o bind em `0.0.0.0:47820` falha; o app
  abre normal, o chat fica bonito e vazio, e nada do celular jamais chega.
  Como a porta é fixa, o X só esconde a janela (`on_window_event`) e o
  autostart está ligado, abrir o atalho com o app já na bandeja é o caminho
  provável para isso: a segunda instância perde o bind e vira uma janela
  morta. Este ticket é quem ganha uma tela de estado do Par (o QR), então é
  onde cabe dizer também "o serviço não está no ar" — e, junto, um guard de
  instância única (`tauri-plugin-single-instance`) que foca a janela existente
  em vez de abrir a segunda. Sem isso, o critério 1 de SYN-002 falha sem
  nenhum sinal para o usuário.

- 2026-09-20 Attempt 1 stopped to ask: Same hard blocker confirmed: `cargo --version` still returns "This command requires approval" and is denied, exactly as SYN-003 and SYN-004 hit. SYN-005's Primary files are all Rust (`pc/src-tauri/src/**`, `Cargo.toml`) and the gate runs `cargo fmt/clippy/test` — stage 2's red-before-green proof and gate check are impossible without cargo execution. /  / Per the loop's rules, this isn't a "Humano simulado" case (no fable-high substitute for a real compiler run), so I'm stopping before creating a branch or touching the ticket, same as the last two sessions. /  / I need one of: / 1. You approve `cargo` execution in this session (Bash or PowerShell) — this is environment-wide, so worth checking whether it's a fixable permission setting. / 2. You run `cargo`/`.\gate.ps1` on your end at checkpoints I flag and relay output back. /  / Which do you want? /

- 2026-09-20 (resposta do humano): **opção 1 — permissão concedida, `cargo`
  liberado.** Era permissão, não ambiente: o `sweatshop.ps1` monta
  `--allowedTools` com `git`, `gh`, `powershell` (primeiro token do gate) e
  `npx`; `cargo` nunca esteve na lista — por isso o stage 3 passava (só chama
  o gate via `powershell`) e o stage 2 morria. Corrigido em
  `.claude/settings.json` (`Bash(cargo:*)`, `Bash(gradlew.bat:*)`,
  `Bash(.\gradlew.bat:*)`), commit `66ed735` nesta branch de sessão. Rode
  `cargo` direto, sem pedir aprovação. Toolchain confere: VS Build Tools
  (MSVC/linker) e rustup instalados; `gate.ps1` passou verde nesta máquina
  hoje às 09:59 (8 testes — 4 em `config.rs`, 4 em `server.rs` — mais `tsc
  --noEmit`). JDK 17 (Microsoft) e `JAVA_HOME` prontos para quando `android/`
  existir (SYN-007). Nada ficou pendente das tentativas anteriores: o driver
  reverteu tudo, não há branch `syn-003`/`syn-004`/`syn-005` órfã. Stage 2
  começa do zero, fluxo normal.

- 2026-09-20 Attempt 1 stopped to ask: `cargo` is still denied in this session — confirmed via both `Bash` (with and without `dangerouslyDisableSandbox: true`) and `PowerShell`, same "This command requires approval" wall SYN-003/004 hit. `.claude/settings.json` does have `Bash(cargo:*)` (and `gradlew.bat`) allowed, so the rule exists but isn't being honored before a prompt would even reach you — looks like the same permission-matching bug flagged twice already, not something the earlier settings fix (commit `66ed735`) actually resolved. /  / I haven't touched any files — only checked out branch `syn-005` off the session branch, no commits. SYN-005's Primary files are Rust, so stage 2 needs `cargo test`/`cargo build` for red-before-green proof; I can't fake that. /  / I need one of: / 1. You look into why the Bash/PowerShell tools deny `cargo`/`gradlew.bat` even with the allow rule present (this is the third session in a row hitting it — SYN-003, SYN-004, now SYN-005). / 2. You run `cargo test --manifest-path pc/src-tauri/Cargo.toml` and `.\gate.ps1` yourself at checkpoints I flag, and relay output back. /  / Which do you want? /

- 2026-09-20 (resposta do humano, corrigindo a anterior): **`cargo` não foi
  liberado — a resposta acima estava errada.** O driver `sweatshop.ps1` passa
  `--allowedTools`, que *restringe* o conjunto: só entram `git`, `gh`, `npx` e
  `powershell` (primeiro token do gate). Regra `allow` em
  `.claude/settings.json` não amplia isso — medido, o `66ed735` está inerte.
  Não chamem `cargo` direto: é negado e não há como liberar de dentro do repo.
  Solução, commit `0845832` nesta branch: `gate.ps1` ganhou `-TestsOnly` (só
  testes, sem fmt/clippy/tsc). **Runner de testes do stage 2:**
  `powershell -ExecutionPolicy Bypass -File .\gate.ps1 -TestsOnly` — já
  liberado porque entra por `powershell`. Verificado: passou, 8 testes (4 em
  `config.rs`, 4 em `server.rs`). O gate completo (sem o switch) continua
  obrigatório antes de `Stage: to-review`; `-TestsOnly` é só para a prova
  red-before-green. `AGENTS.md` ("Bindings do fluxo") já diz isso. Nada
  pendente das tentativas anteriores: driver reverteu tudo, sem branch órfã.

- 2026-09-20 (stage 2, notas para o revisor): **Par, QR e mDNS
  implementados**, gate completo verde (fmt, clippy, 22/22 testes — 4 em
  `config.rs`, 18 em `server.rs` — e `tsc --noEmit`). Sessão interativa, não
  o driver; `cargo` direto seguiu negado mesmo aqui, então usei o mesmo
  runner do binding (`gate.ps1 -TestsOnly` para o vermelho, gate completo
  antes de fechar).

  `par::Par` (novo módulo) é a alça viva: nome de instância + token, criados
  uma vez por `Par::load_or_create` e gravados em `config.rs` (dois campos
  novos em `ConfigFile`). `server::TokenCell` é o que deixa a rotação valer
  sem reiniciar o servidor — aceita tanto o `String` fixo que os 15 testes
  antigos já passavam para `server::spawn` (nenhum deles mudou) quanto um
  `Par`, compartilhando a mesma célula (`Arc<Mutex<String>>`); `authorized()`
  lê sempre o valor atual. Os 3 testes do "Tests stage 2 writes" viraram 3
  funções em `tests/server.rs` (+ um `sobe_com_par` ao lado de `sobe()`),
  commit `test:` isolado, vermelho por `pc_lib::par` não existir — confirmado
  antes de escrever qualquer código.

  mDNS (`anuncio::Anuncio`, crate `mdns-sd`) registra `_synchronice._tcp` com
  o nome de instância do Par e endereço auto-detectado; QR (`qr::par_svg`,
  crate `qrcode`, feature `svg`) serializa `{instance_name, port, token}`
  como JSON — formato meu, não ditado pelo ticket, escolhido por ser
  decodificável sem ambiguidade pelo Android quando SYN-007 existir. Tela de
  QR (`pc/index.html`, `main.ts`) troca de lugar com o chat: aparece sozinha
  se `estado_par_inicial` disser que o Par não existia antes desta abertura,
  ou sob demanda pelo botão ⟳ no composer (`parear_de_novo`, rotaciona e
  devolve o SVG novo); some quando a presença fica online — presença só
  acontece com token válido, então é o sinal de que o pareamento vingou.

  **Não implementado, de propósito:** o achado da revisão de SYN-002 (guard
  de instância única, tela de "servidor não está no ar") segue só em
  `## Comments`, nunca dobrado no corpo do ticket — nem em Primary files nem
  em critério numerado. Por regra do loop, comentário não dobrado é nota, não
  requisito; não tratei como escopo deste ticket. Se for para entrar, precisa
  virar critério + Primary files (aqui ou em ticket próprio) antes de um
  stage 2 pegar.

  **Manual, como o próprio ticket já marca:** critérios 1, 2 e 3 (parte
  visual — QR aparece, `dns-sd -B` enxerga o serviço, novo QR depois de
  parear de novo). Não tenho como abrir o app Windows nem rodar `dns-sd`
  nesta sessão; o gate verde é a garantia que dá para dar de dentro daqui.
  Critério 4 (sobrevive a reiniciar) e 5 (nome sem informação pessoal) estão
  cobertos: 4 por `recriar_o_par_do_mesmo_diretorio_devolve_o_mesmo`, 5 por
  construção (`synchronice-<8 hex aleatórios>`, sem hostname nem usuário do
  Windows).

  Débito humano: critérios 1, 2 e 3 (parte visual) — abrir o app, escanear o
  QR de verdade e rodar `dns-sd -B _synchronice._tcp` na mesma rede.

#### Resolution (2026-09-20)

Verdict: Needs your call: o QR nunca apareceria na janela (o `hidden` das duas
telas não valia nada) — corrigi, mas é a terceira coisa seguida que só a mão
humana confirma, e a spec pede descartar os Pendentes ao parear de novo, o que
nenhum critério deste ticket cobre.

**Achados corrigidos aqui** (pequenos: dentro dos Primary files, sem teste
novo — interface está fora do teste automático por decisão da spec), commit
`75c308d`:

1. ❗ **`hidden` não valia nada nas duas telas** (`pc/ui/styles.css`).
   `#frame` e `#qr-screen` passaram a nascer com o atributo `hidden`, mas os
   dois têm `display:flex` vindo do autor, que vence o
   `[hidden]{display:none}` da folha do navegador. As duas telas ficariam no
   ar ao mesmo tempo, sobrepostas (`position:absolute; inset:0` nas duas), com
   o `#frame` pintando por cima por vir depois no DOM: **o QR do primeiro uso
   nunca apareceria, e o botão ⟳ rotacionaria o token sem mostrar nada** —
   critérios 1 e 3 (parte visual) falhariam em silêncio. Corrigido com
   `[hidden]{display:none!important}`. Nenhum portão automático pega isto: o
   gate não abre a janela e o `tsc` não olha CSS.
2. **Janela em branco quando o servidor não sobe** (`pc/ui/main.ts`).
   `estado_par_inicial` era esperado sem `catch`; com o servidor fora do ar o
   `Par` nunca é gerenciado, o comando falha, `main()` aborta antes de
   `ligarComposer/ligarDrop/ligarColar/ligarParear` e — agora que as duas
   telas nascem `hidden` — a janela fica **em branco**, pior que o chat vazio
   de antes. É exatamente o modo de falha que o comentário da revisão de
   SYN-002 já apontava. Agora cai no chat quando o comando falha.
3. **Contrato do pareamento não documentado** (`pc/src-tauri/src/server.rs`).
   ADR-0001: "o protocolo entre os lados precisa ser documentado, pois ninguém
   compila os dois de um único fonte". O formato do QR
   (`{"instance_name","port","token"}`) e os nomes do mDNS
   (`_synchronice._tcp.local.`, host `<instance_name>.local.`) são contrato
   com o Android e só existiam no código e numa nota do stage 2. Documentados
   no mesmo cabeçalho onde SYN-002/003/004 documentaram os seus.

**Achados não corrigidos** (fora dos critérios deste ticket; stage 1 decide se
viram critério aqui ou ticket próprio):

4. **Pendentes não são descartados ao parear de novo.** A spec, em "Um Par":
   "Parear de novo gera token novo e invalida o anterior. **A fila de
   Pendentes é descartada junto: nada destinado ao Par antigo vai para o
   novo.**" `Par::rotate` troca só o token; o `Outbox` não tem como ser
   esvaziado e ninguém tenta. Na prática, o que estava na fila para o celular
   antigo drena para o novo assim que ele conectar. Precisa de teste novo e de
   uma decisão (descartar só os Pendentes, ou também o arquivo copiado na
   pasta interna?), então não é conserto de revisão.
5. **O QR só aparece na primeiríssima abertura.** `mostrar_qr` é
   `!config::par_ja_existe`, e `Par::load_or_create` grava o token na mesma
   abertura — quem abrir o app e não escanear vê o chat em todas as aberturas
   seguintes, sem QR. Pelo texto do critério 1 ("sem Par gravado") está
   cumprido, e o botão ⟳ é a saída; pelo glossário, porém, o Par é o *vínculo*
   com o celular, e um token que o PC cunhou para si não é vínculo. Um
   `pareado: bool` gravado quando o celular autentica pela primeira vez
   resolveria — critério novo, não conserto.
6. **`⟳` com o celular fora da rede prende o usuário na tela de QR**: só um
   evento de presença volta para o chat. Falta um caminho de volta.
7. **Glossário desatualizado** (`CONTEXT.md`, fora dos Primary files): o verbete
   **Par** não menciona o nome de instância, que agora é parte persistida e
   visível dele.
8. **Cheiros, não defeitos** (julgamento, nada bloqueante): `sobe_com_par`
   repete `sobe()` linha a linha em `tests/server.rs`; `qr::par_svg(par…)`
   aparece idêntico em `estado_par_inicial` e `parear_de_novo` (cabia um
   `Par::qr()`); `ParJaExistia` + `config::par_ja_existe` lê `config.json` só
   para espiar o token um instante antes de `Par::load_or_create` lê-lo de
   novo; `#faedc8` está escrito em `qr.rs` e como `--card` no CSS.

**Critérios.** 1 ✅ (depois do achado 1; parte visual por conferir à mão),
2 ✅ (por construção, `anuncio.rs` registra `_synchronice._tcp` com o nome do
Par; por conferir à mão), 3 ✅ (`rotacionar_troca_o_token_e_o_servidor_recusa_o_antigo_e_aceita_o_novo`
prova a recusa imediata; a `TokenCell` compartilha o `Arc` do `Par`, então vale
em produção, não só no teste), 4 ✅
(`recriar_o_par_do_mesmo_diretorio_devolve_o_mesmo`), 5 ✅ (`synchronice-<8
hex>`, sem hostname nem usuário do Windows).

**Vermelho-verde.** Commit de teste isolado `e540b9d` (só
`pc/src-tauri/tests/server.rs`), vermelho porque `pc_lib::par` não existia;
código em `b955bbf` sem tocar em teste. Confirmado pelo `diff --stat` dos dois
commits.

**Gate** (`powershell -ExecutionPolicy Bypass -File .\gate.ps1`), rodado antes
e depois dos consertos da revisão: `gate: passou` — fmt, clippy, 22 testes
(4 em `config.rs`, 18 em `server.rs`, 0 doc-tests) e `tsc --noEmit`.

Débito humano: abrir o app e confirmar que a tela de QR aparece mesmo (achado
1 foi corrigido às cegas, sem rodar a janela), que ⟳ mostra um QR diferente, e
rodar `dns-sd -B _synchronice._tcp` na mesma rede para ver o serviço.
