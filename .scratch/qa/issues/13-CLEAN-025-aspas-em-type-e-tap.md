# CLEAN-025: Aspas em `type` e `tap -Value` do celular
Stage: done
Status: ready-for-agent
Blocked by: none
Review: agent
Difficulty: normal
Origin: revisão do PR 8 (sessão sweatshop/2026-09-30-2146), 2026-10-01

- Primary files:
  - qa/phone.ps1 (`type` e `tap`)
  - qa/dump.mjs (se o valor passar a chegar por stdin ou arquivo)
  - qa/phone.test.mjs
  - docs/agents/qa.md

#### What to build

Dos cinco itens que a revisão juntou neste ticket, a triagem de 2026-10-03
deixou aqui só o das aspas e mandou os outros para outros lugares:
- `Stop-Process` sem espera e o launcher do `pc.ps1 start` foram para o
  CLEAN-026;
- a Lixeira do Windows relatada sempre foi para o CLEAN-019;
- o clipboard sobrescrito virou `wontfix`, documentado (critério 4).

**`tap -Value`.** O valor vai para o `node dump.mjs` como argv nativo, e a
camada de argv do PS 5.1 come as aspas: `'say "hi"'` chega como `say hi`, o nó
não é achado e o agente registra um ❌ falso (histórias 15 e 17). O valor passa
a chegar ao `dump.mjs` sem passar pela linha de comando do Windows, por stdin
ou por arquivo, como o `pc.ps1` já faz com o JSON.

**`type`.** O texto vai num argv para o adb, e as aspas duplas somem na
camada nativa do PS 5.1. O `type` passa a escapar `"` para essa camada, como o
`Shell-Quote` já faz.

#### Acceptance criteria

1. `tap -By text -Value 'say "hi"'` acha o nó cujo `text` é `say "hi"`, num
   dump com esse nó, e toca no centro dos bounds dele.
2. `tap` com um valor sem aspas continua funcionando como hoje.
3. `type -Text 'say "hi"'` faz o shell do aparelho receber
   `input text 'say%s"hi"'`, com as aspas preservadas.
4. `docs/agents/qa.md`, nas linhas de `paste-image` e `paste-files`, diz que
   as duas sobrescrevem o clipboard do usuário e não o restauram.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

- Em `qa/phone.test.mjs`, um caso de `tap` com o fake `adb.ps1` devolvendo um
  dump com `text="say &quot;hi&quot;"` e bounds conhecidos. O teste confere o
  `input tap` recebido (critérios 1 e 2). O `node` é nativo, então a perda de
  aspas aparece de verdade no vermelho.
- Para o `type`, o fake `.ps1` não passa pela camada nativa. O stage 2 usa um
  fake nativo, ou o padrão do teste 4: o comando gerado roda em git-bash com
  um `input` falso que grava o argv (critério 3).

## Comments

- Stage 2, retomada (2026-10-04): seam confirmado pela revisão: wrapper
  `tap` em `qa/phone.test.mjs`, com fake adb e parser de produção. Callers
  conferidos: CLI de `dump.mjs`, wrapper `tap` e imports em `dump.test.mjs`.
  Regressão usa `text='say "hi"'` e bounds do emulador; preserva os casos
  de entidades, texto simples e apóstrofo dentro de atributo com aspas duplas.
  Vermelho focado: 3 passaram, 2 falharam (caso novo e seu teste pai), com
  `elemento nao achado: text=say "hi"`. Bounds vazios/inválidos e nó ausente
  continuam cobertos pela suíte existente de `findNode`.
- Retomada concluída: teste vermelho em `1a0611f`, correção isolada em
  `9c7a758`. Parser aceita atributos delimitados por aspas simples ou duplas;
  os 10 testes/subtestes focados de tap e findNode passaram. Gate completo
  fora do sandbox: código 0, fmt/clippy/typecheck, 83 testes Rust, 61 da UI,
  88 QA e `gradle check` aprovado (testes Android UP-TO-DATE). Log local:
  `.scratch/qa-runs/CLEAN-025-stage2/gate-reopened.log`. Diff revisado e
  `git diff --check` limpo; apenas Primary files e ticket alterados. A
  confirmação da correção no emulador permanece para o stage 3.

- Stage 2 (2026-10-04): callers examinados: o wrapper `tap` chama a CLI de
  `dump.mjs`; `findNode` também é importado por `dump.test.mjs`. A CLI conserva
  o argumento posicional existente e aceita JSON por stdin quando ele falta.
  `type` reutiliza `Shell-Quote`, sem alterar os demais callers desse helper.
  Seletores simples, espaços, apóstrofos e caracteres de shell foram cobertos.
- Prova red/green: `88911fb` reproduz `elemento nao achado: text=say "hi"`
  (texto simples passa); `d73bf86` deixa os 3 testes/subtestes verdes.
  `c9c561a` reproduz o comando nativo `input text 'say%shi'`, perdendo aspas;
  `497c3f0` deixa os 5 testes/subtestes de type verdes. Os dois grupos juntos:
  8 passaram, 0 falharam. O fake de type passa o comando pela camada nativa
  real do PS 5.1 para node e executa o resultado em git-bash com `input` falso.
  Testes e produção estão em commits separados.
- Gate completo: `powershell -ExecutionPolicy Bypass -File .\gate.ps1` passou
  fora do sandbox: fmt, clippy, typecheck, 83 testes Rust, 61 testes da UI,
  86 testes/subtestes QA e `gradle check` (testes Android UP-TO-DATE;
  relatórios XML existentes somam 166 testes). A primeira execução no sandbox
  falhou por Access denied nos testes do Windows e rede negada ao Gradle;
  essas falhas desapareceram na execução sem sandbox. Logs locais em
  `.scratch/qa-runs/CLEAN-025-stage2/gate.log` e `gate-sandbox.log`.
  Diff final conferido: somente Primary files e este ticket; `git diff --check`
  sem erros. QA com aparelho/emulador fica para o stage 3.

- Itens pequenos achados na mesma revisão. A triagem pode dividir:
  - `qa/phone.ps1:317` (`type`) e `:307` (`tap -Value` para o node) não
    escapam `"` para a camada de argv do PS 5.1. `'say "hi"'` chega como
    `say hi` com código 0, e o agente pode registrar um ❌ falso.
  - `Stop-Process -Force` sem `Wait-Process` em `qa/up.ps1:27,115` e
    `qa/down.ps1:34`. O check seguinte, ou o bind da 47820, ainda pode ver o
    processo morrendo.
  - `qa/pc.ps1:156-161` espera o PID do launcher por 6 s. Se o prazo estoura,
    o launcher ainda abre o PC depois, possivelmente já após o `down`.
  - `qa/pc.ps1:86-90` sempre relata `Lixeira do Windows`, mas sem Lixeira no
    drive, ou acima da cota, o delete é permanente. Afeta só arquivos que o kit
    rastreou, mas o relatório da história 10 fica errado. O caso do MediaStore
    já está em CLEAN-019.
  - `paste-image`/`paste-files` sobrescrevem o clipboard do usuário sem
    restaurar.

- Triagem 2026-10-03 (/grill-proxy, com o humano):
  > *This was generated by AI during triage.*
  Os cinco itens foram confirmados. Divisão do humano (Q9): (a) fica aqui;
  (b)+(c) viram o CLEAN-026; (d) foi para o CLEAN-019; (e), o clipboard, é
  wontfix, porque o kit precisa do clipboard real e restaurar formatos
  arbitrários é frágil. A nota de doc do (e) virou o critério 4 deste
  ticket (Q18, proxy).

#### Review stage 3 (2026-10-04)

Verdict: Reopen — critério 1: `tap` não encontra o texto com aspas no XML real do uiautomator.

- Standards: 0 achados; Spec: 1 achado, confirmado no emulador. A revisão
  estática inicial não identificou o delimitador simples que o QA real revelou.
- ❌ Critério 1: o composer contém exatamente `say "hi"`, com bounds
  `[215,1328][789,1475]` (centro `(502, 1401)`), mas o XML serializa o atributo
  como `text='say "hi"'`. `findNode` reconhece apenas atributos delimitados
  por aspas duplas e devolve `null`. Duas tentativas reais de
  `tap -By text -Value 'say "hi"'` saíram com código 1 e
  `elemento nao achado: text=say "hi"`.
- ✅ Critério 2: seletor `CLEAN-025 plain` tocado com código 0 no emulador;
  enviado e recebido no PC isolado.
- ✅ Critério 3: o gate verifica o comando nativo
  `input text 'say%s"hi"'`; o adb real preservou as aspas no composer e no
  Item entregue ao PC.
- ✅ Critério 4: as duas linhas de clipboard documentam a sobrescrita sem
  restauração.
- Restante para o stage 2: acrescentar uma regressão em `qa/phone.test.mjs`
  com `text='say "hi"'` e corrigir a leitura de atributos em `qa/dump.mjs`,
  mantendo o caso `text="say &quot;hi&quot;"` já coberto. Exige novo teste,
  portanto a revisão não fez o fix. Continuar nesta branch, somente com o
  item ❌; contrato e demais critérios preservados.
- Callers, caminhos de falha e interações examinados: wrapper `tap`, CLI
  `dump.mjs`, importação de `findNode`, argv posicional, stdin JSON/UTF-8,
  seletor ausente, nó ausente, bounds inválidos, falha do parser e temporários;
  `type`, validação ASCII/`%`, `Adb-Run` e demais callers do helper inalterado
  `Shell-Quote` (hash, upload, MediaStore, limpeza). QA real cobriu composer,
  envio e entrega no PC. Unicode em `tap` e JSON inválido por stdin não
  receberam testes específicos nesta revisão.
- Fluxo e validação: commits de testes precedem as respectivas correções;
  commits de produção não alteram testes; diff de implementação limitado aos
  Primary files e ao ticket. Rebase sobre `sweatshop/2026-10-03-1608` já
  atualizado. Gate completo código 0: fmt, clippy, typecheck, 83 testes Rust,
  61 da UI, 86 QA e `gradle check`; testes Android `UP-TO-DATE`, 166 nos XML
  existentes, zero falhas/erros. O caso real que reprova ainda não é coberto
  pelos novos testes verdes.
- Relatório: `.scratch/qa-runs/2026-10-04-clean-025-review.md`. `up` com o
  SDK do projeto confirmou presença 200; `down` encerrou o emulador e o PC
  isolado (código 0, `stopped=true`). A primeira tentativa de `up`, com SDK
  padrão ausente, foi infraestrutura e não motivou a reprovação.

#### Resolution (2026-10-04)

Verdict: Approve

- Re-review do item ❌ e do diff desde `b3cb058`: Standards 0 achados;
  Spec 0 achados, por revisores independentes. A correção reconhece os dois
  delimitadores XML e mantém os seletores existentes.
- ✅ Critério 1: no emulador, `text='say "hi"'` foi encontrado;
  `tap -By text -Value 'say "hi"'` saiu com código 0. O trace nativo do
  PowerShell confirma `shell input tap 502 1401`, centro dos bounds
  `[215,1328][789,1475]`.
- ✅ Critério 2: `CLEAN-025 plain` continua encontrado e tocado no centro,
  com código 0. Os dois textos chegaram ao PC e as bolhas ficaram Entregue.
- ✅ Critério 3: o teste nativo confere `input text 'say%s"hi"'`; no
  emulador, `type` preservou as aspas no composer e no Item recebido no PC.
- ✅ Critério 4: `paste-image` e `paste-files` documentam que sobrescrevem
  o clipboard do usuário e não o restauram.
- Arquivos de implementação: `qa/phone.ps1`, `qa/dump.mjs`,
  `qa/phone.test.mjs` e `docs/agents/qa.md`, dentro dos Primary files.
  Testes vermelhos em `88911fb`, `c9c561a` e `1a0611f` precedem as correções
  `d73bf86`, `497c3f0` e `9c7a758`; commits de produção não alteram testes.
  A regressão da retomada falhou antes do fix (3 passaram, 2 falharam,
  contando o teste pai) e passou no gate. No XML real salvo, a CLI de
  `b3cb058` retorna `null`; a atual retorna `{x:502,y:1401}`.
- Callers e limites reexaminados: wrapper `tap`, CLI posicional/stdin,
  importação de `findNode`, seletores id/texto/descrição, entidades, atributo
  vazio, nó ausente e bounds inválidos; apóstrofo dentro de atributo duplo.
  `type`, `Shell-Quote` e os demais critérios não mudaram nesta retomada.
  O kit confirmou composer, envio, entrega e DOM do PC; sem novas lacunas
  além das já registradas na primeira revisão.
- Gate completo em primeiro plano: código 0, `gate: passou`; fmt, clippy,
  typecheck, 83 testes Rust, 61 da UI e 88 QA. `gradle check` aprovado;
  testes Android `UP-TO-DATE`, 166 testes nos 17 XML existentes, zero falhas
  e erros. Diff revisado e `git diff --check` limpo.
- QA: `.scratch/qa-runs/2026-10-04-clean-025-rereview.md`. `up` compilou
  os apps desta branch com SDK do projeto e confirmou presença 200; `down`
  terminou com código 0 e `stopped=true`. Emulador e PC isolado encerrados;
  lista de arquivos de usuário criados/apagados vazia, sem débito humano.
- Branch atualizada sobre `sweatshop/2026-10-03-1608` e integrada sem squash
  no merge `87baa7e`; fechamento e ledger registrados juntos na sessão.
