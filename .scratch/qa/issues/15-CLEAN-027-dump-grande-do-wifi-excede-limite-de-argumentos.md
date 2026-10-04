# CLEAN-027: Dump grande do Wi-Fi excede o limite de argumentos
Stage: done
Status: ready-for-agent
Blocked by: none
Review: agent
Difficulty: normal
Origin: QA da revisão CLEAN-023, 2026-10-03

- Primary files:
  - qa/phone.ps1 (script gerado do `wifi-off`)
  - qa/phone.test.mjs

#### What to build

O `wait_wifi` do script do `wifi-off` guarda o `dumpsys wifi` inteiro numa
variável e o passa como argumento de `printf`. No mksh do aparelho o `printf`
não é embutido, e o Linux limita cada argumento a 131072 bytes; o dump real
do `SynchroNice_API37` tem ~343 KB, e o `wifi-off` falha com `Argument list
too long` antes de confirmar o estado.

O `wait_wifi` passa a nunca levar o dump a um argumento de comando. Um
`dumpsys` que sai diferente de 0 continua falhando o passo na hora, sem
retentativa. A forma é livre; se usar arquivo temporário, o `restore` o apaga
junto com o script. Evitar `dumpsys | grep -q` sob `pipefail`: o `grep -q`
sai cedo, o `dumpsys` toma SIGPIPE e o pipeline dá falso negativo.

#### Acceptance criteria

1. Com um dump de 400 KB ou mais contendo a linha `Wi-Fi is <estado>`, o
   `wifi-off` confirma que desligou e que restaurou (cenário `large-dump`).
2. Um `dumpsys` que sai diferente de 0 falha o passo sem retentativa: no
   cenário `status-denied`, o `clock` falso continua 0.
3. Os cenários atuais do teste 4 continuam passando.
4. Stage 3, pelo kit no `SynchroNice_API37`: `qa/phone.ps1 wifi-off` sai 0 e
   o log tem `QA_WIFI_DISABLED` e `QA_WIFI_RESTORED`. Falha de
   infraestrutura não reprova o ticket.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

Em `qa/phone.test.mjs`, teste 4, o harness passa a emular o shell do
aparelho:
- o script roda como `sh -c 'enable -n printf; . ./captured.sh'` em vez de
  `sh captured.sh`, e um `printf` falso no diretório do fake sai com
  `Argument list too long` quando um argumento passa de 131072 bytes; nos
  outros casos faz `exec /usr/bin/printf "$@"`. O proxy conferiu no git-bash
  que, assim, o código atual falha e uma variante com arquivo passa;
- cenário `large-dump`: o `dumpsys` falso emite 400 KB ou mais com a linha
  de estado (critério 1, vermelho hoje);
- no `status-denied`, a asserção de `clock` igual a 0 (critério 2).

## Comments

- Stage 3 do CLEAN-023 (2026-10-03): no AVD `SynchroNice_API37`, shell
  `/system/bin/sh` mksh R59, a CLI real chegou ao `svc wifi disable`, mas
  `printf '%s\n' "$state"` falhou com `Argument list too long`. O wrapper
  terminou com `Wi-Fi nao confirmou desligar e restaurar`; o script não chegou
  ao sleep pedido. Uma medição posterior de `dumpsys wifi | wc -c` retornou
  343513 bytes. A restauração também executou o mesmo caminho de falha.
- O `wait_wifi` é idêntico ao da base `168ffb9`, anterior ao CLEAN-023.
  A mudança revisada acrescenta traps/retries; não altera a captura do dump
  nem o `printf`. Achado anterior, sem reabertura do CLEAN-023.
- Filtrar a saída real do `dumpsys` para a linha `Wi-Fi is ...`, somente no
  ambiente controlado da verificação, permitiu confirmar os sinais e retries.
  Evitar passar o dump inteiro como um argumento é uma possibilidade para
  triagem; a solução e os testes ainda precisam ser especificados.
- Evidências: [relatório do CLEAN-023](../../qa-runs/2026-10-03-emulator-CLEAN-023-review.md),
  arquivos locais `2026-10-03-emulator-CLEAN-023-review/runtime/signal-TERM/cli.stderr.log`
  e `environment.log`. O Redmi não foi acionado.
- Proxy decided: aceito (normal, Review: agent); wait_wifi nunca passa o dump como argumento, dumpsys com erro falha sem retentativa, vermelho comportamental no harness (printf embutido desligado e printf falso com limite de 128 KiB por argumento) e wifi-off real no emulador como verificação — o bash do harness tem printf embutido, então só emulando o mksh do aparelho o teste reproduz o defeito; checagem estática do script testaria a forma, não o comportamento.

- Stage 2 (2026-10-04): callers examinados: desligamento e `restore` no
  script gerado, ambos via `wait_wifi`; consumo dos marcadores pelo wrapper
  após reconectar. Casos cobertos: estado atrasado/desconhecido, recusa
  silenciosa, erro de dumpsys, sinais INT/TERM e retries da restauração.
- Vermelho no commit de testes `093aa7c`: execução focada do cenário de
  estado (`node --test --test-name-pattern='wifi-off confirms'
  qa/phone.test.mjs`) teve 13 subcenários passando e `large-dump` falhando
  com `Argument list too long` (Node: 15 testes, 13 pass, 2 fail incluindo
  o teste pai). `status-denied` manteve clock 0. O gate `-TestsOnly`
  confirmou a mesma falha, mas também sofreu restrições do sandbox:
  `Access denied` nos testes do PC e rede negada no Gradle.
- Implementação (`0d8cb54`): here-document entrega a variável de estado ao
  stdin do `grep`; nenhum argumento grande nem arquivo temporário adicional.
  A captura de `dumpsys` mantém o retorno imediato se o comando falhar.
- Verde: `powershell -ExecutionPolicy Bypass -File .\gate.ps1`, fora do
  sandbox, saiu 0 com `gate: passou`: fmt, clippy, typecheck, 83 testes Rust,
  61 testes da UI e 96 testes do kit QA passaram; `large-dump` e os 13
  subcenários anteriores passaram, incluindo clock 0 em `status-denied`.
  Android `check`: `BUILD SUCCESSFUL in 21s`, testes UP-TO-DATE; os relatórios
  existentes registram 166 testes, sem falhas nem erros. As falhas de acesso
  do sandbox desapareceram nesta rodada.
- Handoff: testes e produção em commits separados; diff limitado aos Primary
  files e ao ticket, `git diff --check` limpo. Critério 4 fica para o stage 3
  no `SynchroNice_API37`; stage 2 não iniciou o kit real, conforme a spec.

#### Resolution (2026-10-04)
Verdict: Approve

- Decisão: os quatro critérios foram atendidos. Branch `clean-027` integrada
  localmente à sessão `sweatshop/2026-10-04-1210`, merge `37108e9`, preservando
  os commits de testes e produção. Nenhuma mudança de produção no stage 3.
- Standards: um desvio formal de processo de baixo impacto, sem achados de
  código ou smells. `to-review` foi registrado no commit de handoff separado
  `6d57192`, em vez do último commit de código, como prevê a tabela da skill
  `ticket-flow`. Estágio committed, gate verde e test-first preservado;
  desvio registrado sem reescrever o histórico e sem motivo de reopen.
- Spec: zero achados. Critério 1 ✅: dump de 409600 bytes mais cabeçalhos,
  confirmação dos dois estados e duração solicitada. Critério 2 ✅:
  `status-denied` mantém clock 0. Critério 3 ✅: os 13 cenários anteriores
  passaram. Critério 4 ✅: CLI real no `SynchroNice_API37`, exit 0, duração
  padrão de 15 s e log `QA_WIFI_DISABLED`/`QA_WIFI_RESTORED`; dump integral
  medido antes da chamada com 170040 bytes, Wi-Fi enabled depois.
- Examinados: callers de desligamento e restore; traps EXIT/INT/TERM;
  dumpsys nonzero, dump grande/vazio/sem estado válido, estados atrasados,
  recusa silenciosa, retries da restauração, reconexão/mDNS e leitura dos
  marcadores. Não exercitados: aparelho físico, telas e presença durante a
  queda; fora dos critérios deste ticket. A linha `Proxy decided` existente
  sobre harness, consulta sem espera e QA real foi conferida contra as
  evidências; não houve nova decisão de proxy nesta revisão.
- Red-green independente: testes atuais contra o wrapper da base `ab85fe7`,
  numa cópia isolada: 15 testes, 13 pass e 2 fail (`large-dump` e o pai),
  falha correta `Argument list too long`. Na branch corrigida, teste de
  estado 15/15, incluindo os 14 subcenários. Testes chamam a produção.
- Gate em primeiro plano:
  `powershell -ExecutionPolicy Bypass -File .\gate.ps1`, exit 0,
  `gate: passou`; fmt, Clippy e TypeScript passaram;
  Rust 83/83, UI 61/61, QA 96/96. Android `check`: `BUILD SUCCESSFUL in 14s`,
  testes UP-TO-DATE; relatórios das tarefas de teste com 166 testes em
  17 suítes, sem falhas, erros ou skips.
- QA: `up` com SDK físico saiu 0, APK e PC debug compilados, PC isolado
  online; presença posterior HTTP 200. `down` saiu 0, estado active false,
  PC isolado e AVD encerrados. Sem arquivos de usuário criados ou apagados,
  sem temporários de Wi-Fi restantes e sem débito humano.
- Arquivos: produção em `qa/phone.ps1`, testes em `qa/phone.test.mjs`;
  evidências e revisão em [relatório do CLEAN-027](../../qa-runs/2026-10-04-emulator-CLEAN-027-review.md).
  Alteração de finais de linha de `Cargo.toml` pelo build revertida após
  confirmar ausência de mudança de conteúdo; diff final sem alterações
  de fonte fora dos Primary files, `git diff --check` limpo.
