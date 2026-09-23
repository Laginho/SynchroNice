# SYN-011: PC — Copiáveis
Stage: to-implement
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
