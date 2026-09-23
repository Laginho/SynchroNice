# SYN-018: Android — Copiáveis
Stage: to-implement
Status: ready-for-agent
Blocked by: SYN-011, SYN-013
Review: agent

- Primary files:
  - android/app/src/main/** (função de trechos, bolha de texto, toques, opção na engrenagem)
  - android/app/src/test/** (testes unitários locais)
  - android/app/build.gradle.kts (só para os testes lerem `testdata/copiaveis.json`)
  - testdata/copiaveis.json (só acrescentar casos; qualquer caso novo tem que passar no PC também)

#### What to build

Spec: `.scratch/android/spec.md`, histórias 39 a 48, e a seção "Copiáveis".
Termo: **Copiável** no `CONTEXT.md`.

A mesma função de trechos do PC (SYN-011), escrita em Kotlin, com o mesmo
contrato. Os testes unitários locais do módulo do app (JVM, sem emulador) a
rodam contra o **mesmo arquivo de casos** que o PC usa. Nenhum caso fica só de
um lado.

Na bolha, link e Copiável ficam no azul de destaque; o link é sublinhado e o
Copiável não. Tocar num Copiável copia; num link, abre o navegador; o toque
longo num link copia o link; tocar no resto da bolha copia o texto inteiro.

A engrenagem ganha "Copiar com pontuação", desligada por padrão e persistente.

#### Acceptance criteria

1. A função de trechos em Kotlin devolve, para cada caso de `testdata/copiaveis.json`, exatamente os trechos esperados.
2. `gate.ps1` roda esses testes (também com `-TestsOnly`) e falha se eles falharem.
3. CPF, CNPJ e telefone aparecem formatados na bolha mesmo que o texto recebido não tenha pontuação.
4. Tocar num Copiável copia só os dígitos; com "Copiar com pontuação" ligada, copia a forma formatada.
5. Tocar num link abre o navegador, o toque longo nele copia o link, e tocar no resto da bolha copia o texto inteiro.
6. Link é azul sublinhado e Copiável é azul sem sublinhado, iguais ao PC.
7. "Copiar com pontuação" aparece na engrenagem, começa desligada e persiste.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1 -TestsOnly
    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 3 a 7 são no aparelho: verificação manual.

## Tests stage 2 writes (own commit, red)

- `android/app/src/test/`: um teste que lê `testdata/copiaveis.json` e roda a
  função Kotlin contra cada caso. Red porque a função ainda não existe (ou não
  acha nada).

## Comments
