# SYN-020: Bandeja de envio no PC antes de mandar
Stage: implementing
Status: ready-for-agent
Blocked by: BUG-003
Review: agent
Difficulty: normal

- Primary files:
  - New: pc/ui/bandeja.ts
  - New: pc/ui/bandeja.test.ts
  - pc/ui/main.ts (paste, drop, Enter, ➤, Esc)
  - pc/ui/styles.css, pc/index.html (a bandeja acima do composer)
  - gate.ps1 (roda `bandeja.test.ts` junto com `copiaveis.test.ts`)
  - pc/src-tauri/src/lib.rs (comando `previa_de_caminho`, registro no
    `generate_handler!` e um teste no `mod tests` que já existe). Sem mexer em
    `Cargo.toml`, `tauri.conf.json`, capabilities ou no protocolo.

#### What to build

Pedido no walkthrough de QA (2026-09-30), no jeito do WhatsApp. Hoje, Ctrl+V
de imagem ou arquivo e o drop enfileiram na hora. Com a bandeja, o que é colado
ou solto fica numa bandeja acima do composer. Dá para juntar texto, imagens e
documentos, tirar o que entrou por engano, e só o Enviar manda tudo. Cada
entrada continua sendo um Item separado (a spec proíbe agrupar); só saem
juntas.

Decisões do stage 1 (2026-10-01):

- **Tudo passa pela bandeja:** Ctrl+V de imagem, Ctrl+V de arquivo do
  Explorer e o drop. Colar texto puro cai no campo, como hoje.
- **Enviar.** Enter e o ➤ que já existe mandam a bandeja, mesmo com o campo
  vazio. Não há botão novo.
- **Ordem de envio:** a ordem de entrada na bandeja, com o texto por último,
  como legenda.
- **Tirar e limpar.** Cada entrada tem um ✕. O mesmo arquivo que entra duas
  vezes vira duas entradas. O Esc fecha as configurações se elas estiverem
  abertas; senão, limpa a bandeja e deixa o texto do campo. Cada Esc faz uma
  coisa só.
- **Vida da bandeja.** Ela só existe no estado JS: sobrevive a esconder a
  janela, e morre com o processo e no recarregar, como o texto do composer. Não
  é persistida.
- **Cópia para a fila.** O arquivo é lido e copiado no Enviar, não ao entrar
  na bandeja. O print colado guarda os bytes até lá. Uma entrada cuja origem
  sumiu fica na bandeja, marcada como não encontrada, e o resto vai, sem toast
  novo. No Enviar, o `main.ts` chama `send_files` com **um caminho por
  entrada** (e `send_bytes` para o print), em sequência, para saber qual
  falhou; os dois comandos não mudam.
- **Arrasto de volta.** Todo caminho que alimenta a bandeja mantém o guard
  `arrastandoDaBolha`: soltar na janela o arquivo arrastado de uma bolha não
  entra na bandeja.
- **Visual.** Chips acima do composer: miniatura nas imagens, nome e tamanho
  nos documentos, ✕ em cada um. O polimento fica para o `/impeccable`.
- **Prévia do chip (proxy, 2026-10-02).** Drop e Ctrl+V de arquivo só trazem
  caminhos, e a janela não tem como ler um caminho. Um comando novo e
  somente leitura, `previa_de_caminho(caminho) -> { tamanho, bytes }`, chamado
  uma vez por entrada quando ela entra na bandeja, devolve o tamanho e, só
  para imagem (as extensões de `IMAGEM` do `main.ts`) de até 10 MiB, os bytes
  para a miniatura (`bytes: null` nos outros casos). Imagem maior vira chip de
  documento. Não copia nem enfileira nada: os bytes da prévia nunca vão para o
  `send_bytes`, e a cópia para a fila continua sendo feita a partir do caminho,
  no Enviar. Se a prévia falhar, o chip mostra só o nome, sem marca: "não
  encontrada" só aparece depois de um Enviar. O print colado já tem os bytes e
  o tamanho no `File`, então não passa pelo comando.
- **Lógica num módulo puro,** `bandeja.ts`, testado pelo executor nativo do
  Node como o `copiaveis.ts`. O `main.ts` só liga eventos e renderiza.

#### Acceptance criteria

1. Adicionar entradas mantém a ordem de entrada. Remover uma posição tira só
   aquela. O mesmo arquivo adicionado duas vezes vira duas entradas.
2. A sequência de envio é a das entradas, com o texto por último. Texto vazio
   ou só com espaços não entra.
3. Limpar esvazia a bandeja.
4. Depois de um envio em que uma entrada falhou, só ela fica na bandeja,
   marcada; as outras saem.
5. Na janela, Ctrl+V de imagem, Ctrl+V de arquivo e o drop põem a entrada na
   bandeja, e nada é enviado.
6. Na janela, Enter e o ➤ mandam tudo na ordem do critério 2, inclusive com o
   campo vazio.
7. Na janela, o ✕ tira a entrada. O Esc fecha as configurações se estiverem
   abertas; senão, limpa a bandeja e mantém o texto. Colar texto puro cai no
   campo.
8. A bandeja sobrevive a esconder e reabrir a janela.
9. Os testes existentes seguem verdes.
10. `previa_de_caminho` devolve o tamanho do arquivo; devolve os bytes só para
    imagem de até 10 MiB (`bytes: null` para documento e para imagem maior);
    caminho que não existe dá erro. Nada é copiado para a fila.
11. Na janela, o chip de documento mostra nome e tamanho; o de imagem, colada
    ou por caminho, mostra a miniatura; imagem acima de 10 MiB aparece como
    chip de documento.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

QA no stage 3: kit no emulador, critérios 5 a 8 e 11 pelo CDP. Use as
primitivas `paste-image` e `paste-files` (um `.pdf` e um `.png` juntos) e
confira os chips com `text` e `screenshot`. Se o drop nativo não for
alcançável, ele fica 👤.

## Tests stage 2 writes (own commit, red)

- `pc/ui/bandeja.test.ts` (novo), com `node --test`, critérios 1 a 4.
  Vermelho porque `bandeja.ts` não existe. O `gate.ps1` passa a rodá-lo no
  mesmo commit.
- Um teste no `mod tests` de `pc/src-tauri/src/lib.rs`, critério 10, com
  arquivos no `tempfile` (já é dev-dependency): um `.txt`, um `.png` pequeno,
  um `.png` acima de 10 MiB e um caminho inexistente. Chama a função do
  comando direto, sem `State`. Vermelho porque `previa_de_caminho` não existe.

## Comments

- Stage 2 (2026-10-02): chamadores conferidos: composer, drop nativo e
  colagem; `send_files` é usado uma entrada por vez, sem mudar os comandos
  existentes. O módulo puro terá ids por entrada para duplicatas e para
  não confundir resultados atrasados com novas entradas. Casos de fronteira:
  bandeja vazia, texto só com espaços, remoção repetida, limpar e adicionar
  durante um envio, origem apagada e imagem acima do limite de prévia.
  Primeiro vermelho pelo runner `gate.ps1 -TestsOnly`: PC 57/57, UI antiga
  47/47, kit 14/14 e Android verdes; novo teste falha com
  `ERR_MODULE_NOT_FOUND`, porque `bandeja.ts` não existe.

- Stage 1 (2026-10-01), grilling com o dono e o `proxy`. O dono decidiu que o
  drop também passa pela bandeja, e o `proxy` decidiu o resto. O lado Android
  é o SYN-021, com o mesmo comportamento.
- Bloqueado pelo BUG-003, que muda os mesmos caminhos de envio (`send_files`,
  `send_bytes`) e o `main.ts`.
- Proxy decided: a prévia do chip ganha o comando somente leitura
  `previa_de_caminho` em `lib.rs` (tamanho e, para imagem de até 10 MiB, os
  bytes), com o critério 10 coberto por um teste Rust e o 11 pelo QA no CDP. —
  A janela só recebe caminhos e não tem `fs` nem `assetProtocol`. Ligar um dos
  dois mexe em `Cargo.toml`/config e abre leitura geral de arquivos. Cortar
  tamanho e miniatura desfaria a decisão "Visual" do stage 1.
- Proxy decided: não há seam novo nem módulo separado. — O comando é uma
  função de `lib.rs` como `read_file_bytes`. A única regra com ramo (só
  imagem, até 10 MiB) leva um teste unitário no `mod tests` que já existe. O
  resto é cola de interface, verificada pelo QA como o `main.ts`.

- 2026-10-02 Attempt 1 failed: exit 0. Log tail: SYN-020 is not at `to-review`: the full gate exits 1 because `cargo test` can't run. The fix itself is committed on `syn-020`. /  / - **Fix:** A pasted image over 10 MiB now gets a document chip instead of a thumbnail (criterion 11). The test is commit `113f18e`, red because `temPrevia` didn't exist. The fix is `ef84226`. / - **Gate:** The UI tests (55/55), `tsc`, kit (14/14) and Android all pass. I didn't touch any Rust, so the 58/58 PC result from the last review should stand. / - **Blocker:** `cargo test` fails with `failed to remove ...\target\debug\pc.exe: Access is denied`. Some process holds that file. It could be your real PC app, so I didn't kill it. I couldn't inspect processes, and a run with a separate `CARGO_TARGET_DIR` needed approval. /  / Close whatever is running `pc.exe` (or approve the alternate target dir), then run this and I'll finish: confirm green, set `Stage: to-review`, commit. /  / ```powershell / powershell -ExecutionPolicy Bypass -File .\gate.ps1 / ``` /  / I didn't re-run the native paste QA for a pasted image over 10 MiB. Stage 3 should check that case, along with the small pasted image.
