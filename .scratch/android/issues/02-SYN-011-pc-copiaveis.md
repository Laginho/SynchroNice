# SYN-011: PC — Copiáveis
Stage: done
Status: ready-for-agent
Blocked by: SYN-010
Review: agent

- Primary files:
  - New: testdata/copiaveis.json (casos compartilhados com o Android)
  - New: pc/ui/copiaveis.ts
  - New: pc/ui/copiaveis.test.ts
  - pc/ui/main.ts (renderização da bolha de texto, clique, opção no painel)
  - pc/ui/styles.css
  - pc/index.html (opção no painel da engrenagem)
  - pc/tsconfig.json, pc/package.json (só se o executor de testes exigir)
  - gate.ps1 (rodar os testes do módulo TS, nos dois modos)

#### What to build

Spec: `.scratch/android/spec.md`, histórias 39 a 45, 47 e 48, e a seção
"Copiáveis". Termo: **Copiável** no `CONTEXT.md`.

O texto das bolhas passa por uma função pura que devolve a sequência de
trechos: texto comum, link ou Copiável. O Copiável traz o tipo, o texto exibido
e o texto copiado nos dois modos. A lista de tipos é fechada (CPF e CNPJ com
dígitos verificadores válidos, celular, fixo, e-mail, chave PIX aleatória). As
regras de cada tipo, a regra de limites e a colisão CPF × celular estão na
spec. Nenhum outro número é destacado.

Este ticket cria o **arquivo de casos** que o Android (SYN-018) vai ler
também: cada caso tem a entrada e os trechos esperados. A função TS é testada
contra ele pelo executor de testes nativo do Node, que o gate passa a rodar.

Na bolha, link e Copiável ficam no azul de destaque da paleta; o link é
sublinhado e o Copiável não. Clique sem movimento num Copiável copia o texto
copiado dele. Num link, abre o navegador. No resto da bolha, copia o texto
inteiro. Arrastar o mouse continua selecionando.

O painel da engrenagem (SYN-010) ganha "Copiar com pontuação", desligada por
padrão, que troca o modo de cópia. A opção persiste entre execuções.

#### Acceptance criteria

1. O arquivo de casos existe na raiz e cobre, no mínimo: número comum não destacado, CPF válido e inválido, CNPJ válido e inválido, celular com e sem `+55` e com e sem pontuação, fixo, colisão CPF × celular (celular ganha), dígitos colados a letras e a outros dígitos, e-mail, UUID, link com número dentro, e o texto copiado nos dois modos.
2. A função de trechos devolve, para cada caso do arquivo, exatamente os trechos esperados.
3. `gate.ps1` roda esses testes, também com `-TestsOnly`, e falha se eles falharem.
4. CPF, CNPJ e telefone aparecem formatados na bolha mesmo que o texto recebido não tenha pontuação.
5. Clicar num Copiável copia só os dígitos; com "Copiar com pontuação" ligada, copia a forma formatada. E-mail e chave são copiados como estão.
6. Clicar num link abre o navegador; clicar no resto da bolha copia o texto inteiro; arrastar o mouse seleciona sem copiar.
7. Link é azul sublinhado, Copiável é azul sem sublinhado.
8. "Copiar com pontuação" aparece no painel da engrenagem, começa desligada e mantém o valor depois de reiniciar o app.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1 -TestsOnly
    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 4 a 8 são de tela: verificação manual abrindo o app.

## Tests stage 2 writes (own commit, red)

- `testdata/copiaveis.json` com os casos do critério 1, e
  `pc/ui/copiaveis.test.ts` que roda a função contra cada caso. Red porque o
  módulo `copiaveis.ts` ainda não existe (ou exporta uma função que não acha
  nada). O passo do gate entra no mesmo commit dos testes, para o red aparecer
  no gate.

## Comments

- 2026-09-24 Red-green proof: the test-only commit `dc19ef6` made
  `gate.ps1 -TestsOnly` fail because `pc/ui/copiaveis.ts` did not exist; the
  existing 39 Rust tests and Gradle tests passed. After implementation,
  `gate.ps1 -TestsOnly` passed with 20/20 Node cases, 39 Rust tests (6 config,
  2 drag, 31 server) and Gradle tests. The full gate passed fmt, Clippy, those
  39 Rust tests, `tsc --noEmit`, all 20 Node cases and `gradle check`.
- Débito humano: inspeção ao vivo dos critérios 4–8. A árvore de acessibilidade
  mostrou Configurações na tela de QR, mas a captura visual falhou (`SetIsBorderRequired
  failed: No such interface supported (0x80004002)`) e o clique por índice falhou
  (`coordinate input geometry is unavailable`). O painel e checkbox, o estilo,
  a cópia real, a abertura de link e a seleção por arraste não foram observados
  ao vivo.
- 2026-09-24 Revisão de estágio 3: o app abriu e mostrou a tela de QR na árvore
  de acessibilidade, mas a captura visual e o clique falharam novamente com os
  mesmos erros. O débito visual acima permanece. A validação de e-mail e UUID
  passou a usar os mesmos padrões da busca de candidatos, eliminando duplicação.
- 2026-09-24 Foreman: a revisão mergeou pela `main` (PR #5) porque a sessão
  `sweatshop/2026-09-24-1855` ainda era igual à `main` e contava como mergeada;
  corrigido no driver (Agent-Skills `8ba1ce1`). As duas tentativas de stage 2
  que se seguiram na sessão leram `reviewing` e pararam; o `blocked` delas foi
  desfeito ao trazer a `main` para a sessão.

#### Resolution (2026-09-24)
Verdict: Approve

PR #5 integrado em `main` por `ab76d7b`. Os oito critérios numerados foram
atendidos por inspeção de código e pelos casos compartilhados. O commit
`dc19ef6` contém só testes, casos e gate e falhou pela ausência do módulo;
os commits de implementação não alteraram testes. Após a implementação, o gate
passou com 39 testes Rust, 20 casos Node, `tsc --noEmit` e `gradle check`.

Arquivos: `testdata/copiaveis.json`, `pc/ui/copiaveis.ts`,
`pc/ui/copiaveis.test.ts`, `pc/ui/main.ts`, `pc/ui/styles.css`, `pc/index.html`
e `gate.ps1`. A revisão removeu a duplicação dos padrões de e-mail e UUID.
O débito visual dos critérios 4–8 registrado acima permanece; o Computer Use
reproduziu a falha de captura e não conseguiu clicar no painel. O PR não tinha
checks de CI configurados.
