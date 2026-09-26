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

1. ❌ A função de trechos em Kotlin devolve, para cada caso de `testdata/copiaveis.json`, exatamente os trechos esperados. Falta cobrir e igualar ao PC links com porta fora de `1..65535` e escapes `%` malformados.
2. `gate.ps1` roda esses testes (também com `-TestsOnly`) e falha se eles falharem.
3. CPF, CNPJ e telefone aparecem formatados na bolha mesmo que o texto recebido não tenha pontuação.
4. Tocar num Copiável copia só os dígitos; com "Copiar com pontuação" ligada, copia a forma formatada.
5. Tocar num link abre o navegador, o toque longo nele copia o link, e tocar no resto da bolha copia o texto inteiro.
6. ❌ Link é azul sublinhado e Copiável é azul sem sublinhado, iguais ao PC. O Android usa `#7ba7ff`; o PC usa `#1b4fa8` nesses trechos.
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

#### Stage 2 (2026-09-26)

- Implementada a função pura `segmentarTexto` em Kotlin com o contrato compartilhado do PC. A bolha formata e destaca os trechos, copia Copiáveis nos dois modos, abre links e copia links no toque longo. O restante da bolha copia o texto original inteiro; ações de acessibilidade oferecem as mesmas operações.
- "Copiar com pontuação" começa desligada e persiste em `settings`, separado do Par.
- Red: commit de testes `521cc87`; `gate.ps1 -TestsOnly` falhou em `compileDebugUnitTestKotlin` por ausência de `Trecho` e `segmentarTexto`. A primeira execução restrita falhou antes por acesso a `C:\.android`; a execução com acesso ao perfil confirmou o vermelho esperado.
- Green: `gate.ps1` passou com fmt/clippy, 44 testes Rust, typecheck TS, 24 casos Node, 54 testes do protocolo Android, 9 testes JVM do app e lint Android. O teste novo do app percorre todos os 24 casos de `testdata/copiaveis.json`; nenhum teste foi alterado no commit de implementação.
- Débito humano: verificar no aparelho os critérios 3–7 (formatação e cores, copiar nos dois modos, abertura e toque longo de links, cópia do restante da bolha e persistência após reiniciar). Computer Use foi inicializado e não encontrou janela de emulador; `adb devices`, pelo SDK configurado em `android/local.properties`, não encontrou aparelho conectado. A interação visual não foi verificada nesta etapa.

#### Stage 3 review (2026-09-26)

- Critério 1: `java.net.URI` diverge do `URL` do PC nos limites do contrato. Por exemplo, o Android aceita `https://example.com:99999`, que o PC rejeita, e rejeita `https://example.com/%zz`, que o PC aceita. O próximo commit de testes deve acrescentar ambos ao arquivo compartilhado antes de corrigir Kotlin.
- Critério 6: a cor de destaque do Android não é a cor aplicada a links e Copiáveis no PC.
- Standards: sem bloqueio. A preferência persistente do Android é exigida pela spec Android posterior à ADR-0002; o modelo simples de `Trecho` espelha diretamente o JSON compartilhado, sem justificar uma hierarquia selada.
- Reaberto porque o critério 1 exige novo teste; a etapa 3 não pode escrevê-lo.
