# QA — 2026-10-03 — CLEAN-018 — re-revisão stage 3

Branch: `clean-018`, entrega `5634a94`, base fixa `31e1024` da sessão
`sweatshop/2026-10-03-1608`. Delta da reabertura: `248fa8f...5634a94`.
Logs e dados locais: `2026-10-03-clean-018-rereview/` (ignorado pelo git).

## Critérios executáveis

Os testes executam a CLI real de `qa/phone.ps1` em Windows PowerShell 5.1,
com config sintético e `adb.ps1` falso que captura argv, na seam aprovada.
Nenhum teste ou arquivo de produção foi alterado nesta re-revisão.

| Critério / caso | Veredito | Evidência em texto |
| --- | --- | --- |
| 1 — campos ausentes, nulos, vazios ou malformados | ✅ | Treze execuções: exit 1, `config.json sem Par valido`, zero `am start` |
| 1 — arrays vazios ou com um valor válido, nos dois campos | ✅ | Quatro execuções: exit 1, mensagem de Par inválido, zero lançamento |
| 1 — LF final no nome ou no token | ✅ | Duas execuções: exit 1, mensagem de Par inválido, zero lançamento |
| 2 — `10.0.0`, `010.0.0.1`, `256.1.1.1`, `0.0.0.0` | ✅ | Exit 1 e zero lançamento em cada caso |
| 3 — config e IPv4 explícito válidos | ✅ | Um único `DebugPairActivity --es qr`; JSON inteiro igual à entrada, porta 47820 |
| 4 — IPv4 omitido | ✅ | IP do JSON igual ao lookup UDP calculado localmente, sem IP fixo |
| 5 — `Error:` ou `Exception` do `am`, mesmo com exit 0 | ✅ | Duas execuções: um lançamento, wrapper sai 1 e relata `pair falhou` |

`node --test --test-name-pattern='pair validates' qa/phone.test.mjs`:
**12 testes passaram, zero falhas**, com 27 execuções do wrapper.
Log: `proof/green.log`.

## Prova independente do vermelho

Os testes atuais foram copiados, sem mudanças, para um diretório local
ignorado. O `phone.ps1` veio de `e97e45a`, antes da correção `763c6dc`.
A execução deu exit 1: **5 testes passaram e 7 falharam** (os seis casos
novos e o pai). Cada caso novo recebeu exit 0 e `Starting: Intent`, em vez
da rejeição esperada. Os demais casos passaram, descartando uma falha
geral do harness. Log: `proof/red.log`.

O histórico mantém os testes em `e97e45a` e a correção de produção em
`763c6dc`, sem alteração de testes no commit de código. A cobertura inicial
sem vermelho segue a exceção explícita do ticket.

## QA nativo no emulador

`up` e `down` usam o SDK direto de `android/local.properties`:
`D:\AppData\Local\Android\Sdk`. RunDir:
`.scratch/qa-runs/2026-10-03-clean-018-rereview`.
As chamadas aguardaram seu resultado em primeiro plano, com acesso ao
ambiente fora do sandbox necessário para os builds e o kit.

| Passo / critério | Veredito | Evidência em texto |
| --- | --- | --- |
| Preparação da branch revisada | ✅ | APK debug: `BUILD SUCCESSFUL`, 38 tasks up-to-date; PC debug compilado com sucesso em 7m 59s; `up` exit 0 |
| 3 — `pair` com IPv4 explícito pelo `up` | ✅ | `am start` da DebugPairActivity: `Status: ok`, `LaunchState: COLD`, chat aberto; `up` confirmou `online=true` |
| 4 — `pair` sem `-Ip` pelo adb real | ✅ | Wrapper exit 0 e `Status: ok`; o adb informou entrega do intent à instância já aberta |
| 3–4 — campos persistidos no Android | ✅ | Leitura de SharedPreferences do app debug: `instanceNameMatches=true`, `tokenMatches=true`, `port=47820`, `routeIpMatches=true`; valores sensíveis não foram emitidos |
| 3–4 — presença nos dois aparelhos | ✅ | PC: HTTP 200 e `online=true`; dump Android contém `Na rede` |
| Teardown | ✅ | `down` exit 0, `stopped=true`, `restored=false`; nenhuma porta 5554/5555/47820/9222 continuou escutando |

O fake `.ps1` continua fora da camada nativa de argv, conforme o contrato.
Esta revisão verificou também o transporte real PowerShell/adb e o Par
recebido pelo app, com configuração isolada. Nenhum token real foi lido
ou incluído no relatório. Logs: `up.log`, `pair-default.log`,
`pair-fields.json` e `phone.xml`.

## Gate

A primeira execução no sandbox passou Rust **83**, PC/UI **61** e QA
**26**, mas o Gradle não pôde acessar a rede (`Permission denied:
getsockopt`). É uma limitação do ambiente, não uma falha da implementação.
Log: `gate.log`.

O gate completo após o QA e o rebase foi repetido com acesso ao ambiente
necessário e terminou em primeiro plano com **exit 0**, `gate: passou`:

- Rust: **83 testes passaram**, zero falhas; fmt e clippy passaram.
- PC/UI: typecheck passou e **61 testes passaram**, zero falhas.
- QA: **26 testes passaram**, zero falhas.
- Android: `BUILD SUCCESSFUL in 18s`, **41 tasks**, 1 executada e 40
  up-to-date. `testDebugUnitTest` e `protocol-client:test` ficaram
  up-to-date; seus XMLs somam **94 + 72 = 166 testes**, zero falhas,
  erros ou skips.

Log final: `gate-final.log`. A integração na sessão não mudou código
em relação à branch validada.

## Revisão e limpeza

Standards encontrou uma violação documental: os comentários do stage 2
da reabertura estavam antes da revisão anterior. Corrigido em `822cf11`,
conforme o append cronológico do tracker. Zero smells acionáveis.
Spec encontrou zero requisitos faltantes, desvios, implementações
incorretas ou regressões; os dois achados anteriores do critério 1 estão
resolvidos. Nenhuma nova decisão de proxy.

Limpeza: lista vazia de arquivos de usuário criados, enviados ou apagados.
O harness removeu seus temporários em `finally`. Dados, logs e artefatos
isolados permanecem no RunDir local ignorado. Emulador e PC isolado foram
encerrados; nenhuma instância release precisava ser restaurada.
O build Tauri normalizou as linhas de `Cargo.toml`; o blob foi confirmado
idêntico ao HEAD e o arquivo restaurado, sem mudança de conteúdo.
Nenhum débito humano nesta re-revisão.
