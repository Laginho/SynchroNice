# SYN-020: Bandeja de envio no PC antes de mandar
Stage: to-implement
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
  novo.
- **Arrasto de volta.** Todo caminho que alimenta a bandeja mantém o guard
  `arrastandoDaBolha`: soltar na janela o arquivo arrastado de uma bolha não
  entra na bandeja.
- **Visual.** Chips acima do composer: miniatura nas imagens, nome e tamanho
  nos documentos, ✕ em cada um. O polimento fica para o `/impeccable`.
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

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

QA no stage 3: kit no emulador, critérios 5 a 8 pelo CDP. Use a primitiva de
paste do kit. Se o drop nativo não for alcançável, ele fica 👤.

## Tests stage 2 writes (own commit, red)

- `pc/ui/bandeja.test.ts` (novo), com `node --test`, critérios 1 a 4.
  Vermelho porque `bandeja.ts` não existe. O `gate.ps1` passa a rodá-lo no
  mesmo commit.

## Comments

- Stage 1 (2026-10-01), grilling com o dono e o `proxy`. O dono decidiu que o
  drop também passa pela bandeja, e o `proxy` decidiu o resto. O lado Android
  é o SYN-021, com o mesmo comportamento.
- Bloqueado pelo BUG-003, que muda os mesmos caminhos de envio (`send_files`,
  `send_bytes`) e o `main.ts`.
