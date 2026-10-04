# QA — 2026-10-03 — Emulador — CLEAN-023

Infraestrutura: AVD `SynchroNice_API37`, endpoint `127.0.0.1:5555`, adb do SDK
em `D:/AppData/Local/Android/Sdk`, shell `/system/bin/sh` mksh R59,
`nohup` do toybox e PC debug da branch `clean-023` com CDP 9222 e dados
isolados em `2026-10-03-emulator-CLEAN-023-review/data`. A branch foi
conferida sobre a base da sessão `168ffb9`; o rebase estava atualizado.
`up` saiu 0, `online: true`; presença do PC antes e depois dos probes: HTTP 200.

## Standards

Três desvios de registro de baixo impacto, sem violação de escopo ou TDD:

- Comentários do stage 2 foram inseridos antes da triagem. O tracker exige
  acrescentar histórico no fim (`docs/agents/issue-tracker.md`); a ordem foi
  corrigida nesta revisão.
- Os quatro commits do stage 2 não têm corpo explicando o motivo e citando o
  ID, como pede `ticket-flow`. `342f48c` também omite o ID no assunto.
- O handoff `to-review` foi commitado em `342f48c`, separado do último commit
  de código `836cc3d`, em vez da transição no último commit de código prevista
  pela tabela do `ticket-flow`. A transição é committed e registra gate verde.

Nenhum desses registros afeta os critérios ou a separação exigida de testes
e código. Os commits de teste `f458769`/`713d817` antecedem a implementação;
`836cc3d` toca somente `qa/phone.ps1`, sem testes. Não houve reescrita do
histórico. A mudança de produção ficou no script gerado do `wifi-off`, com
nomes claros e laço limitado; nenhum smell material encontrado.

Examinados: diff integral e por commit, `wait_wifi`/`restore`, traps, launcher
nohup, descoberta/reconexão sem fio, polling dos marcadores, limpeza e handler
de erro do PC, além dos cenários com comandos falsos.

## Spec

Zero achados no contrato. Os critérios 1–4 foram exercitados no Android;
o critério 5 foi confirmado no gate completo.

| Critério | Veredito | Evidência em texto |
| --- | --- | --- |
| 1 — TERM durante sleep | ✅ | Sinal enviado ao PID do `nohup sh` após início de `sleep 6`; trace `sleep 6 -> exit 1 -> restore`. Um `svc wifi enable` e um `QA_WIFI_RESTORED`, CLI exit 0. |
| 2 — INT durante sleep | ✅ | Mesmo lançamento assíncrono e trace `sleep 6 -> exit 1 -> restore`. Um enable e um marcador; sinal enviado com sucesso, CLI exit 0. |
| 3 — duas falhas e sucesso | ✅ | Wrapper do `svc` falhou nas chamadas 1/2 e delegou a terceira ao comando real. Três enables, estado real confirmado como enabled, um marcador e CLI exit 0. |
| 4 — cinco falhas | ✅ | Cinco enables recusados pelo wrapper, sem `QA_WIFI_RESTORED`; CLI exit 1 com `Wi-Fi nao confirmou desligar e restaurar: QA_WIFI_DISABLED`. Wi-Fi reativado pelo comando real ao terminar a verificação. |
| 5 — cenários anteriores | ✅ | No gate, os nove cenários anteriores passaram: delayed, stuck-disabling, disable-refused, restore-refused, status-denied, dump-denied, unknown, success e log-pending. O teste completo passou 14/14: 13 cenários e o pai. |

O probe usa o wrapper de produção `phone.ps1 wifi-off -Seconds 6`, adb real
e o script gerado sem mudanças. Uma ponte local conta/delega `svc`, marca o
início do sleep real, preserva o launcher `nohup sh ... &`, envia o sinal e
aguarda o PID para registrar o resultado. As duas leituras adicionais com
`sh -x` confirmam a execução de `exit 1` e a restauração única. No mksh, o
status final observado pelo `wait` foi 0; os critérios não fixam código de
saída do script. A prova da interrupção está no trace.

Limitação da prova Android: o dump integral de Wi-Fi excedeu o limite de
argumentos do `printf`, antes de chegar ao sleep. Essa função é idêntica à
base anterior; o achado foi registrado para triagem no
[CLEAN-027](../qa/issues/15-CLEAN-027-dump-grande-do-wifi-excede-limite-de-argumentos.md).
Nos probes seguintes, um wrapper filtra somente a linha real `Wi-Fi is ...`
do `dumpsys`, sem simular o estado e sem mudar o script gerado. Portanto a
CLI integral ainda tem essa limitação anterior. Nenhum Redmi foi acionado.

Prova independente red: cópia dos testes atuais contra o wrapper da base
`168ffb9`, em diretório local isolado, falhou pelos quatro cenários novos:
TERM, INT, enable-retry e enable-failed. Resultado 9/14 passados, 5/14 falhos
(quatro cenários e o pai), exit 1. Os nove cenários anteriores passaram.
Green: os testes atuais passaram no gate, incluindo 14/14 no teste focado,
com os quatro cenários novos e os nove anteriores. Os testes chamam a CLI
e o script gerado de produção.

Gate de encerramento em 2026-10-04, em primeiro plano:
`powershell -ExecutionPolicy Bypass -File .\gate.ps1`, exit 0, `gate: passou`.
Formatação Rust, Clippy e TypeScript passaram; Rust 83/83, interface 61/61 e
QA 73/73, sem falhas ou skips. Android: `BUILD SUCCESSFUL`, 41 tarefas,
uma executada e 40 up-to-date, incluindo testes reutilizados pelo Gradle.
Log local: `2026-10-03-emulator-CLEAN-023-review/gate.log`.

Callers/interações examinados: launcher assíncrono e sinais durante sleep,
EXIT/restore, confirmação das duas transições, negativas e estados
desconhecidos, atrasos/timeouts, retries, reconexão/mDNS, log parcial e erro
reportado no PC. Sem alteração de contrato, sem `Proxy decided` e sem
regressão encontrada na mudança. O aparelho físico permanece fora desta
revisão; o problema do dump integral ficou explicitamente separado.

Evidências locais: `runtime-results.json`, `runtime-trace-results.json`,
`runtime-filtered/*/calls.log`, `runtime-trace/*/cli.stdout.log`,
`runtime/*/cli.stderr.log`, `environment.log`, `red/red.log`, `up.log`,
`up-retry.log`, `presence-after.log` e `down.log`, sob o diretório homônimo
ignorado pelo git. A primeira chamada de preparação parou na captura de
stderr do PowerShell; repetida com essa captura corrigida, compilou os dois
apps e confirmou presença. Configuração/QR e tokens permanecem locais.

Limpeza: lista vazia de arquivos de usuário criados/enviados/apagados.
Os scripts, logs e contadores temporários desta verificação em
`/data/local/tmp` foram removidos ao final de cada probe; o Wi-Fi foi
reativado pelo comando real. `down` com o mesmo SDK/RunDir saiu 0,
`stopped: true`, `restored: false`; estado `active: false`, PC debug e AVD
encerrados. O PC real não estava aberto antes do primeiro `up`. Evidências
isoladas ficaram preservadas. A conversão de finais de linha de
`Cargo.toml` gerada pelo build foi revertida após conferir diff sem conteúdo.

Standards: 3 desvios de registro de baixo impacto, com cronologia corrigida;
Spec: 0 achados. Revisão aprovada; merge local da branch do ticket na sessão
`sweatshop/2026-10-03-1608`: `3bfba74`.
