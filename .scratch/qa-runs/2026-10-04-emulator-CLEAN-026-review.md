# QA — 2026-10-04 — emulador — CLEAN-026

Código final: `0a91d4b`. Revisão completa desde `092e14c`, incluindo a
correção da tentativa interrompida (`f3436e6`, rebaseada como `e3bc975`).
Integração à sessão `sweatshop/2026-10-03-1608`: `89b13cf`.

Infraestrutura: AVD `SynchroNice_API37`, endpoint `127.0.0.1:5555`, PC debug
isolado, CDP 9222. SDK físico `D:/AppData/Local/Android/Sdk`, conforme
`android/local.properties`. A instância real do PC estava fechada antes
do QA e permaneceu fechada ao final.

## Standards

Nenhuma violação documentada pendente. A exceção aprovada no ticket dispensa
teste automatizado novo e commit vermelho para estas mudanças de ciclo de vida.
As correções permanecem nos três Primary files, sem mudança de API ou configuração.

Uma heurística não bloqueante: **Duplicated Code**, nas três cópias de
`Stop-AndWait` em `qa/up.ps1`, `qa/down.ps1` e `qa/pc.ps1`. O acompanhamento
já existe no CLEAN-030, recuperado pelo proxy; nenhuma extração foi feita
neste ticket. Os flags de timeout e de fase da parada têm escopo por processo
e nomes que descrevem seu propósito. O diff final foi conferido, e o ruído
de fim de linha que o build gerou no `Cargo.toml` foi removido.

## Spec

Três achados corrigidos, nenhum pendente:

1. **PID vazio — critério 2:** `AutomationNull` permitia seguir ao CDP e,
   no modo real, gravar `pid=0`. `aeb080f` normaliza a leitura para string
   e exige PID positivo antes do estado/CDP. A reprodução anterior devolveu
   `CDP indisponivel`; a versão final devolveu o erro correto do PID.
2. **Timeout silenciado no helper — critério 1:** um processo que saísse
   depois de `WaitForExit(10000)` devolver falso podia apagar o erro no catch.
   `5ce2e13` retém o timeout nas três cópias e preserva a mensagem com PID.
3. **Timeout silenciado no catch do filho — critério 1:** a segunda camada
   podia descartar o erro já classificado pelo helper. `0a91d4b` separa a
   captura da identidade da parada e repropaga integralmente o erro.

O segundo e o terceiro achados não foram capturados nos fechamentos iniciais
do eixo Spec desta retomada; ambos foram relidos antes do veredito final.
Não houve scope creep de comportamento.

Chamadores e interações examinados: `up` → rebuild/start/presence/rollback,
`down` → encerramento/restauração real antes de falhas de adb, start isolado
e real, prontidão CDP, estado ausente/inativo e os 14 cenários do down.
Falhas examinadas: processo já encerrado, PID ausente/tardio, launcher
encerrado ou não autorizado, captura de identidade, timeout, falha após PID,
CDP indisponível e reutilização do PID do pai ou do filho. Os handles,
a autorização exclusiva antes de criar o filho e a janela de criação dos
filhos foram conferidos por leitura.

## Critérios e evidência

| Critério / passo | Veredito | Evidência |
| --- | --- | --- |
| 1 — paradas por `Stop-Process` enumeradas no ticket | ✅ | Todas passam por `Stop-AndWait`, com handle retido, espera de 10 s e erro com PID. Timeout confirmado atravessa os catches, mesmo após uma saída tardia. Conferência por leitura, conforme a exceção aprovada. |
| 2 — PID ausente | ✅ | Script de produção, executável inválido de QA: exit 1, `Launcher do PC nao devolveu o PID`, 7,33 s incluindo inicialização/cleanup; zero filhos e launchers sobreviventes. |
| 2 — falha depois de receber o PID | ✅ | Executável Windows de QA que fica vivo sem CDP: exit 1, `CDP indisponivel`, 46,59 s; zero filhos e launchers sobreviventes. |
| 3 — regressão automatizada | ✅ | Gate completo em primeiro plano, após a última correção: exit 0; Rust 83, UI 61, kit 88, incluindo os 14 cenários do down; fmt, Clippy e typecheck passaram. Android `check`: BUILD SUCCESSFUL, testes UP-TO-DATE. |
| 3 — `up` real no emulador | ✅ | APK e PC debug da branch preparados; `online=true`, `restoredOnDown=false`, exit 0. Nova consulta de presença: `{"status":200,"online":true}`; invoke real `list_items`: `[]`. |
| 3 — `down` real e repetição | ✅ | Duas chamadas com exit 0 e `stopped=true`, `restored=false`. Estado `active=false`; PC, emulador e adb encerrados na conferência final. |

## Execução e limites

Todos os comandos finais usaram `0a91d4b`. O ciclo real usou o mesmo
`-RunDir .scratch/qa-runs/2026-10-04-clean-026-review/emulator-final` em
`up`, nas consultas do PC e nos dois `down`; os wrappers do emulador
receberam `-Sdk D:/AppData/Local/Android/Sdk`.

Não há teste novo nem vermelho automatizado, conforme a exceção explícita
do contrato. Processo resistente à parada, reutilização de PID e a corrida
de saída logo após o timeout foram avaliados por leitura; não foram
reproduzidos fisicamente. Os ensaios de PID e CDP chamam o script de produção.

Uma tentativa inicial de `up` falhou ao executar o adb pelo caminho padrão
que é uma junction para o SDK físico. O rollback terminou; o uso de `-Sdk`
com o caminho já configurado no projeto resolveu a infraestrutura.

Observação preexistente, fora dos pontos `Stop-Process` deste ticket:
o down confirma a saída do emulador pelo adb. Na sonda adicional de 10 s,
um processo auxiliar nativo ainda estava encerrando; essa sonda retornou 1.
A leitura subsequente não encontrou `pc`, `qemu-system-x86_64` ou `emulator`,
nem dispositivos adb ou listeners 5554/5555. O snapshot final, às
04:44:29 -03:00, registra zero PC e zero emuladores. A espera adb do down
não foi alterada por este diff; a observação também foi registrada no SYN-025.

Evidências locais, ignoradas pelo git, sob `2026-10-04-clean-026-review/`:
`gate-complete.log`, `up-final.log`, `missing-pid-final/summary.json`,
`no-cdp-final/summary.json` e, em `emulator-final/`, `presence.log`,
`items.log`, `down.log`, `down-repeat.log`, `summary.json`,
`post-shutdown.json` e `emulator.json`.

Limpeza: nenhum arquivo de usuário criado ou apagado; lista de destinos vazia.
Os executáveis de fixture, dados isolados e logs permanecem como evidência
local ignorada. Nenhum débito humano de UI se aplica a estes critérios.

Standards: 0 violações, 1 heurística de duplicação já no CLEAN-030.
Spec: 3 achados corrigidos, 0 pendentes; critérios 1–3 aprovados.
