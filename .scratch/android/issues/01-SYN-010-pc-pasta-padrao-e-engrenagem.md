# SYN-010: PC — Pasta padrão e engrenagem
Stage: to-review
Status: ready-for-agent
Blocked by: none
Review: agent

- Primary files:
  - pc/src-tauri/src/lib.rs (diálogo do primeiro uso, comando de trocar Pasta)
  - pc/src-tauri/src/config.rs (Pasta padrão quando a configuração não tem uma)
  - pc/src-tauri/src/server.rs (só onde a Pasta ausente é tratada)
  - pc/src-tauri/tests/config.rs
  - pc/src-tauri/tests/server.rs
  - pc/src-tauri/capabilities/default.json (se o diálogo passar a ser chamado pela UI)
  - pc/index.html
  - pc/ui/main.ts
  - pc/ui/styles.css

#### What to build

Spec: `.scratch/android/spec.md`, histórias 34 a 38, e as seções "Pasta" e
"Engrenagem".

O PC deixa de abrir o diálogo de Pasta no primeiro uso. Sem Pasta na
configuração, ele usa `Downloads\SynchroNice` (a pasta Downloads do usuário no
Windows) e cria o diretório quando precisar gravar. Um arquivo recebido no
primeiro uso já cai lá.

Um ícone de engrenagem no canto superior da janela abre um painel de
configurações. Hoje não há cabeçalho: o painel e o ícone são o mínimo para
isso. O painel tem:
- **Mudar pasta…**: abre o diálogo nativo de pasta. A escolha vale para o
  próximo arquivo sem reiniciar, pelo mesmo caminho que já atualiza o servidor
  em execução.
- **Desparear**: o re-pareamento que já existe (token novo, Pendentes
  descartados, QR na tela). O botão ⟳ do composer sai, porque o Desparear o
  substitui.

A bandeja continua só com "Abrir" e "Sair".

#### Acceptance criteria

1. Sem Pasta gravada na configuração, a Pasta efetiva é `SynchroNice` dentro da pasta Downloads do usuário.
2. Um arquivo recebido sem Pasta gravada é escrito nessa pasta padrão, que é criada se não existir.
3. Nenhum diálogo de Pasta abre na inicialização.
4. A engrenagem abre um painel com "Mudar pasta…" e "Desparear"; fechar o painel volta ao chat.
5. ❌ "Mudar pasta…" grava a nova Pasta, e o próximo arquivo recebido vai para ela sem reiniciar o app. O arquivo dos Itens já recebidos precisa continuar acessível na Pasta original: hoje a bolha passa a resolver seu nome na Pasta nova e pode arrastar outro arquivo com o mesmo nome.
6. "Desparear" rotaciona o token, descarta os Pendentes e mostra o QR novo, como o ⟳ fazia.
7. O botão ⟳ do composer não existe mais.
8. O menu da bandeja continua com exatamente "Abrir" e "Sair".

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 3, 4, 7 e 8 são de tela: verificação manual abrindo o app.

## Tests stage 2 writes (own commit, red)

- `pc/src-tauri/tests/config.rs`: Pasta efetiva sem Pasta gravada é
  `<Downloads>/SynchroNice`. Red porque hoje o carregamento devolve "sem Pasta".
- `pc/src-tauri/tests/server.rs`: um arquivo recebido com a configuração sem
  Pasta é gravado na pasta padrão, que é criada. Red porque hoje o servidor sobe
  sem Pasta e recusa ou descarta o arquivo.
- `pc/src-tauri/tests/server.rs`: depois de receber arquivos de mesmo nome em
  Pastas diferentes, cada id de Item continua resolvendo para seu próprio
  caminho e conteúdo. Red porque a UI hoje procura ambos pelo nome na Pasta
  atual.

## Comments

- 2026-09-24 Attempt 1 failed: exit 0. Log tail: Parei no guard de modelo do `ticket-flow`. O SYN-010 está em `Stage: to-implement` e o binding do repo atribui a implementação ao **gpt-6-luna max**. Este runtime está identificado apenas como Codex/GPT-6, então não consigo confirmar que corresponda ao modelo exigido. /  / A árvore está limpa na branch `sweatshop/2026-09-24-1506`. Não fiz alterações. O ticket está em [01-SYN-010-pc-pasta-padrao-e-engrenagem.md](D:/Desktop/Projects/SynchroNice/.scratch/android/issues/01-SYN-010-pc-pasta-padrao-e-engrenagem.md).

- 2026-09-24 Attempt 2 failed: exit 0; blocked after two attempts. Log tail: O `SYN-010` está em `Stage: to-implement`, na branch de sessão `sweatshop/2026-09-24-1506`. O binding do repositório reserva a implementação para **gpt-6-luna max**; este runtime só está identificado como GPT-6, então o guard do `ticket-flow` exige parar sem implementar. /  / A árvore segue limpa e não rodei testes. Para avançar, execute o ticket com o modelo indicado.

- 2026-09-24 De volta a to-implement: o bloqueio era o guard de modelo do `ticket-flow` (Codex não informa o modelo à sessão), corrigido no skill. O ticket em si não tem pendência.
- Débito humano: inspeção visual do chat, painel, seletor nativo, tela inicial sem diálogo e menu da bandeja. O Computer Use falhou ao capturar a janela: `SetIsBorderRequired failed: No such interface supported`.
- 2026-09-24 Inspeção: app encerrado e entrada de autostart removida. A revisão automática bloqueou a exclusão das pastas geradas nesta execução: `%APPDATA%\dev.synchronice.pc`, `%LOCALAPPDATA%\dev.synchronice.pc` e `%TEMP%\SynchroNice-SYN-010`; a limpeza manual ainda é necessária.

#### Review (2026-09-24)

Verdict: Reopen: o critério 5 exige guardar a Pasta de cada Item recebido para que trocar a Pasta afete apenas os próximos arquivos.

- Standards: nenhum achado acionável.
- Spec: o servidor grava o próximo arquivo na Pasta nova, mas `read_file_bytes` e `caminho_para_arrastar` procuram todos os Itens recebidos na Pasta atual. Uma bolha antiga perde a miniatura/arraste, ou aponta para outro arquivo se houver um nome igual. Reabrir para teste de regressão antes do código, no seam de `pc/src-tauri/tests/server.rs`, cobrindo mesmo nome em Pastas diferentes e resolução pelo id do Item. A correção pode ficar nos arquivos primários existentes (`server.rs`, `lib.rs`, `main.ts` e teste).
- Gate: passou (`cargo fmt --check`, `cargo clippy`, 37 testes Rust, `tsc --noEmit`, `gradle check`). A inspeção visual permanece como débito humano registrado acima.
