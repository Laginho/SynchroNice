# QA e revisão — 2026-10-02 — BUG-003

Status: checkpoint; achados reproduzidos, segunda rodada do gate pendente.
Veredito previsto: Reopen — critérios 3/7 e regressão de inicialização.

Branch `bug-003`, implementação em `6cb56d9`, HEAD inicial `4f24026`.
Comparação: `git diff a1d417ac08a20d36893b0b607be9f7f0632df708...HEAD`,
base de sessão `sweatshop/2026-10-01-2019`, merge-base `608165d`.
Contrato: `.scratch/core/issues/21-BUG-003-pc-perde-enviados-ao-recarregar.md`.
Evidências locais ignoradas: `2026-10-02-bug-003-final-review/`.

## Standards

**P2 — a falha da fila interrompe controles independentes.** O novo
`Promise.all` em `pc/ui/main.ts:736–739` rejeita quando `list_sent_items`
falha. `pc/src-tauri/src/lib.rs:330–335` permite esse estado: retorna quando
`Outbox::new` falha, sem registrar `Outbox`, embora `Items` e `Par` já
existam. Não chega à renderização do resultado de `list_items` nem a
`ligarComposer`, `ligarDrop`, `ligarColar` e `ligarConfiguracoes`.
A base inicializava esses controles; o tratamento de falha também está
documentado na resolução de SYN-005. Viola a regra de preservar comportamento
não relacionado do Engineering Workflow fornecido pelo usuário.

Reprodução no executável da branch: configurar `data/outbox` como um arquivo
na execução isolada `failed-outbox`. O stderr registra `os error 183`;
`list_items` devolve `[]`, `list_sent_items` rejeita com `state not managed`,
e dois cliques na engrenagem deixam `settings-panel.hidden=true`.
Evidências: `failed-outbox.json`, `pc-failed-outbox.png` e
`failed-outbox/pc.stderr.log`.

**Sugestão, sem reprovação — possible Repeated Switches.** A direção é
consultada repetidamente por `bubble.classList.contains("mine")` para legenda,
drag, abertura e miniatura (`main.ts:245,271–273,362,368`). Resolver a direção
uma vez poderia reduzir a dependência da classe visual. Não é critério nem
regressão; não autoriza refatoração neste ticket.

Resultado: **1 violação concreta e 1 sugestão heurística**. Pior achado:
interrupção da inicialização quando a fila falha. Produção dentro dos Primary
files; commits de testes e produção separados.

## Spec

**P2 — outra instância apaga o print de uma Sessão viva (critérios 3 e 7).**
O critério 3 exige uma cópia com o nome do Item que continua existindo depois
do ACK; a decisão de ciclo de vida exige que dure até o fim da Sessão.
`Outbox::new` (`outbox.rs:129–135`) e `Inner::drop` (`outbox.rs:100–103`)
removem toda a pasta compartilhada `session-files`. Não há proteção de
instância única, e a criação da fila ocorre antes do bind HTTP.

Reprodução: o PC PID 11352 colou a imagem pelo clipboard real. O print id 3
foi confirmado pelo endpoint real `/outbox/3/ack`, sua cópia existia e a
miniatura tinha `naturalWidth=128`. Abrir o mesmo executável em outro
processo, PID 18736, com o mesmo diretório de dados e um perfil WebView
separado, apagou essa cópia. Ambos os processos continuavam vivos.
`read_sent_file_bytes(3)` na primeira janela passou a rejeitar com
`o arquivo de origem não está mais disponível`; clicar marcou a bolha como
sumida. `list_sent_items` ainda mostrava id 3 como Entregue.
Evidências: `normal.json`, `overlap.json` e `pc-overlap.png`.
O destrutor compartilha a mesma pasta e tem o mesmo risco; a reprodução
executada cobre a abertura da segunda instância, não o destrutor.

**P2 — regressão na inicialização**, também registrada no eixo Standards.
O resultado bem-sucedido de `list_items` e os controles da janela deixam de
ser usados quando a lista de enviados falha. É preservação de comportamento
existente; o cenário normal do critério 6 passou.

Resultado: **2 achados concretos**, um compartilhado com Standards. Pior
achado: perda de prints de uma Sessão ainda viva. Sem outras lacunas concretas
ou escopo extra no diff e nos chamadores revisados. Não foram encontradas
linhas `Proxy decided:`; há o comentário histórico de decisões do stage 1.

## Execução do QA

`qa/up.ps1 -RunDir .scratch/qa-runs/2026-10-02-bug-003-review-codex
-Sdk D:/AppData/Local/Android/Sdk` compilou o APK e o PC da branch.
O pareamento de debug respondeu `Status: ok`, mas a presença não ficou 200
em 30 segundos. O wrapper abortou e executou `down`:
`stopped=true, restored=false`. Falha de infraestrutura, sem reprovação do
ticket por esse motivo.

Depois, o kit abriu o PC isolado em `2026-10-02-bug-003-final-review/`.
Clipboard/CF_HDROP reais pelos wrappers, DOM e reload pelo CDP.
Recebidos e ACKs vieram dos endpoints HTTP reais, como cliente de protocolo;
**esta etapa não comprova entrega pelo app Android**.

| Critério/passo | Veredito | Evidência |
| --- | --- | --- |
| 1, 2, 4, 5 | ✅ | Os quatro testes públicos de `outbox` passaram; origem, texto, limpeza de cópia abandonada e Pendentes herdados estão cobertos. |
| 3, processo único | ✅ | Print colado id 3, cópia com nome `imagem-1790924770797.png`, existente após ACK. |
| 3 e 7, instâncias sobrepostas | ❌ | A segunda instância apagou a cópia do print confirmado da primeira; leitura e abertura falharam. |
| 6, fluxo normal | ✅ | Sete bolhas antes/depois do reload, mesmos lados, `at_ms`, `ordem`, conteúdo e estados; somente um texto Pendente. Comparadas com as duas listas do backend ordenadas por `at_ms`/`ordem`. |
| 7, miniatura e abertura | ✅ | Imagem enviada por CF_HDROP e print colado com `naturalWidth=128`; clique no TXT abriu `qa-source-bug-003.txt - Notepad`. |
| 7, resolução do drag | ✅ | Comando da enviada id 1 devolveu a origem com nome `qa-source-bug-003.png`; leitura devolveu 1593 bytes. Recebidos com ids 1 e 2 coexistiram sem misturar os comandos. |
| 7, drag para fora | 👤 | Computer Use tentado; captura inicial: `FrameArrived timed out: timed out waiting on channel`; seleção/ativação e nova captura: `window capture timed out: timed out waiting on channel`. Nenhum gesto externo foi verificado. |
| 8 | ✅ | TXT removido temporariamente: clique marcou `sumiu` e legenda sem “Pasta”. Restaurado no caminho: próximo clique abriu Notepad, retirou `sumiu` e recuperou `TXT · 25 B`. |
| Inicialização sem fila | ❌ | `list_items` resolveu, `list_sent_items` rejeitou; engrenagem permaneceu sem resposta em duas tentativas. |

Screenshots locais: `pc-normal.png`, `pc-overlap.png`,
`pc-failed-outbox.png`. A ordenação de empate de horário foi inspecionada
no código; os sete Itens deste cenário tiveram horários distintos.

## Validação automática

Test-first conferido pelo histórico: `a53a09a` introduziu o teste da origem
antes de `59b6ead`; `01ffd9e` introduziu texto/prints antes de `f2d5e88`.
`d9972e8` apenas formatou testes. Nenhum commit de produção alterou testes.
Os registros do stage 2 guardam os vermelhos E0599 e 1 verde/3 vermelhos;
essa prova histórica não foi reexecutada nesta revisão.

Primeira rodada do gate completo: **código 1**. PC **55/56**:
4 unitários, 7 configuração, 4 outbox e 40/41 servidor.
`envios_concorrentes_do_mesmo_id_criam_um_item_e_um_arquivo` falhou em
`tests/server.rs:819`, no `read_to_string` do socket, com Windows 10053
(`ConnectionAborted`). Nenhuma asserção de duplicação foi alcançada nessa
falha. `server.rs` e `tests/server.rs` não foram alterados pelo BUG-003;
a origem da instabilidade não foi determinada.

Fmt, Clippy, typecheck, **UI 47/47**, **kit 14/14** e `gradle check` passaram.
Os relatórios Android somam **133 testes**, sem falhas, reutilizados como
`UP-TO-DATE`. Log integral: `gate.log`. Segunda rodada será registrada aqui.

## Limpeza

Antes do envio, a Pasta isolada foi fotografada pelo kit e estava vazia.
Único recebido criado pelo QA, registrado por hash e removido pelo `clean`:

- `D:/Desktop/Projects/SynchroNice/.scratch/qa-runs/2026-10-02-bug-003-final-review/data/Pasta/qa-received-bug-003.png`
  → **Lixeira do Windows**.

Fontes artificiais e evidências continuam no diretório local ignorado.
O TXT foi restaurado após a simulação de arquivo ausente. Processos de PC
11352, 18736 e 21624 e Notepad 22364, identificados por caminho/PID e arquivo
de QA, encerrados. `qa/down.ps1` repetido com o RunDir do emulador devolveu
`stopped=true, restored=false`; `adb devices` ficou vazio.
Nenhum arquivo, Par ou Sessão reais do usuário foi alterado.

## Handoff

As duas correções requerem testes de regressão novos. Pelo limite mecânico
do stage 3, a próxima ação é reabrir o BUG-003, sem corrigir produção nesta
revisão. A limpeza deve preservar prints de sessões vivas e a inicialização
da janela deve sobreviver à falha da lista de enviados. O ledger não tem
linha de BUG-003 para remover. Nenhum merge ou push foi realizado.
