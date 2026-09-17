# SYN-001: Casca do PC
Stage: to-review
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
