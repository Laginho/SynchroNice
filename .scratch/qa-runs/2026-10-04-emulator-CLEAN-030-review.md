# QA — 2026-10-04 — Emulador — CLEAN-030

Branch revisada: `clean-030`, HEAD `de62095`, sobre a sessão
`sweatshop/2026-10-04-1210` (`910d521`). Rebase atualizado, diff integral e
os dois commits de implementação/handoff conferidos. Revisão sem correções
de produção.

## Standards

Zero achados de código e zero smells; um desvio formal leve de processo.
A tabela de transições de `ticket-flow` prevê `to-review` no último commit
de código. Aqui, `89c5f97` implementa e `de62095` registra o handoff em um
commit separado, após o gate verde. O estágio está committed; esse desvio
não viola critérios, Primary files ou a exceção autorizada de testes e não
é motivo mecânico de reopen. Histórico preservado.

A extração respeita o escopo dos quatro Primary files. `qa/process.ps1`
contém somente a função, com corpo idêntico às três cópias anteriores por
comparação de AST, normalizando apenas finais de linha. Os wrappers usam
`$PSScriptRoot`; `Save-State` e `Get-RecordedPc` permanecem intactos.
As suítes existentes não foram alteradas.

## Spec

Zero achados no contrato; os três critérios foram atendidos.

| Critério | Veredito | Evidência |
| --- | --- | --- |
| 1 — definição única carregada pelos três wrappers | ✅ | Busca em `qa/*.ps1`: somente `qa/process.ps1:1` define `Stop-AndWait`. O diff troca cada cópia por `. "$PSScriptRoot/process.ps1"`; os quatro arquivos passam na análise de sintaxe. |
| 2 — execução direta de qualquer diretório | ✅ | Chamadas por caminho absoluto a partir de `TEMP`: up/pc com porta 0 saem 1 com `Porta CDP invalida`, down sem estado sai 0. No ciclo real, up, pc start/presence/invoke e down também rodaram a partir de TEMP, com RunDir absoluto, e saíram 0. |
| 3 — suítes do kit passam sem mudança | ✅ | Gate final: 96/96 testes QA, zero falhas ou skips; inclui os 20 cenários de down, falha de Stop-Process sobrescrito, restauração e retentativas. Nenhum arquivo de teste mudou entre a base e o HEAD. |

Examinados: os chamadores de up na recompilação e parada do PC real;
seleção e identidade dos processos de down, restauração e repetição da
limpeza; start isolado/real do PC, launcher e filhos nos caminhos de erro.
Foram conferidos pipeline vazio, processo encerrado, falha de stop,
retenção do handle, prazo de 10 segundos e erro com PID. O dot-source
preserva a resolução dinâmica do override de `Stop-Process`.
Não exercitados no ciclo real: aparelho físico, câmera, telas e caminhos
de falha do launcher; as falhas de down são cobertas pela suíte existente.
Esses passos visuais não fazem parte dos critérios deste ticket.

A linha existente `Proxy decided` aprovou a extração pequena, Difficulty
normal, Review agent, mesmos prazos/erros e ausência de testes novos da
organização interna. A implementação atende essa decisão. Nenhuma nova
decisão do proxy foi tomada nesta revisão.

## Gate e prova de preservação

Sem prova red-green nova: o ticket autoriza expressamente uma refatoração
pura sem testes adicionais. A função é idêntica à base e as suítes foram
mantidas, incluindo o override dinâmico exercitado em produção.

`powershell -ExecutionPolicy Bypass -File .\gate.ps1` rodou em primeiro
plano, fora do sandbox. Rodada final: exit 0, `gate: passou`;
Rust fmt/Clippy e TypeScript aprovados, Rust 83/83, interface 61/61,
QA 96/96. Android `check`: `BUILD SUCCESSFUL in 23s`, 41 tarefas, uma
executada e 40 up-to-date. Os relatórios XML reutilizados registram
166 testes em 17 suítes, zero falhas, erros ou skips.

A primeira rodada saiu 1: Rust 82/83, falha em
`exclusao_durante_upload_que_falha_descarta_item_e_retry`, conexão local
abortada com erro Windows 10053 em `tests/server.rs:570`. As demais
verificações passaram, incluindo QA 96/96 e Android check. O Rust não faz
parte do diff; a repetição do gate completo passou sem nenhuma alteração.
As duas saídas permanecem nas evidências locais.

## Ciclo real e limpeza

AVD `SynchroNice_API37`, endpoint `127.0.0.1:5555`, SDK físico
`D:/AppData/Local/Android/Sdk`. Up compilou/instalou o APK debug e compilou
o PC debug da branch, com CDP 9222 e dados isolados nesta execução.
Pareamento: `Status: ok`; up saiu 0, `online:true`. Presença antes e depois
do reinício: HTTP 200, `online:true`.

`pc.ps1 start -Isolated` encerrou o PID 8748 antes de abrir o PID 9648.
O processo anterior teve `HasExited=true`; o celular reconectou.
A chamada Tauri `list_items` via CDP saiu 0 e devolveu `[]`.

Down com o mesmo RunDir/SDK saiu 0, `stopped:true`, `restored:false`;
a repetição devolveu o mesmo resultado. Estado `active:false`, nenhum
PC isolado remanescente, emulador ausente do adb e nenhum processo
`qemu-system-x86_64` após a limpeza. O PC real não estava aberto antes do up.
Lista vazia de arquivos de usuário criados, enviados ou apagados no ciclo.

Evidências locais no diretório homônimo, ignorado pelo git: `cwd.log`,
`environment.log`, `gate.log`, `gate-retry.log`, `up.log`,
`presence-before.log`, `pc-restart.log`, `presence-after.log`, `invoke.log`,
`down-1.log`, `down-2.log` e `devices-after.log`. Tokens e configuração
permanecem locais. A conversão de finais de linha de `Cargo.toml` pelo build
foi revertida para os bytes salvos antes do up, após confirmar conteúdo
idêntico. Nenhum artefato gerado integra a revisão.

Standards: 1 desvio leve de processo, 0 achados de código e 0 smells.
Spec: 0 achados. Revisão aprovada.
