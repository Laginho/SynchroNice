# SYN-005: Pareamento por QR e anúncio na rede
Stage: implementing
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
