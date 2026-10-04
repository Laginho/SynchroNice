# QA — 2026-10-04 — Emulador — CLEAN-027

Branch revisada: `clean-027`, HEAD `6d57192`, sobre a base da sessão
`sweatshop/2026-10-04-1210` (`ab85fe7`). Rebase atualizado; diff integral e
separação dos commits conferidos. Revisão sem mudanças de produção.

## Standards

Um desvio formal de processo de baixo impacto; zero achados de código e
zero smells novos. A tabela de transições da skill `ticket-flow` prevê
`to-review` no último commit de código. Aqui, `0d8cb54` implementa a correção
e `6d57192` registra a transição em um commit separado, com gate verde.
O estágio está committed; esse desvio não viola Primary files, test-first
ou comportamento e não é motivo mecânico de reopen. Histórico preservado.

`093aa7c` contém os testes antes da produção; `0d8cb54` altera produção sem
tocar testes. Código restrito a `qa/phone.ps1` e `qa/phone.test.mjs`.
O here-document é uma mudança local e simples: fornece o dump ao stdin do
`grep`, preservando o retorno imediato de `dumpsys` e os caminhos existentes
de restauração. Nenhum arquivo temporário adicional foi introduzido.

## Spec

Zero achados no contrato; os quatro critérios foram atendidos.

| Critério | Veredito | Evidência em texto |
| --- | --- | --- |
| 1 — dump de pelo menos 400 KB | ✅ | `large-dump` emite 409600 bytes mais cabeçalhos. No gate, passou com saída 0, ambos os marcadores, estado final enabled e duração solicitada. |
| 2 — dumpsys nonzero sem espera | ✅ | `status-denied` passou; a asserção exige clock falso igual a 0. A captura mantém `state=$(dumpsys wifi) || return 1` antes de qualquer sleep. |
| 3 — cenários anteriores | ✅ | Os 13 cenários anteriores passaram, incluindo INT/TERM, retries, estados atrasados/desconhecidos, recusas, dump negado e log parcial. Teste de estado: 15/15, contando o pai e os 14 subcenários. |
| 4 — wifi-off real no SynchroNice_API37 | ✅ | CLI de produção, duração padrão de 15 s, exit 0: `{"serial":"127.0.0.1:5555","seconds":15,"reconnected":true,"log":"QA_WIFI_DISABLED\nQA_WIFI_RESTORED"}`. Dump integral medido antes da chamada: 170040 bytes; Wi-Fi enabled antes e depois. |

A decisão existente `Proxy decided` especificou dump fora dos argumentos,
falha imediata de consulta, harness com limite de printf externo e prova
real no emulador. A implementação e as evidências atendem essa decisão;
nenhuma nova decisão foi tomada pelo proxy nesta revisão.

Examinados: os callers `wait_wifi disabled` e `wait_wifi enabled` no restore;
traps EXIT/INT/TERM; retorno nonzero, dump grande, vazio ou sem estado válido;
transições atrasadas, recusa silenciosa, tentativas de restauração,
reconexão/mDNS e consumo dos dois marcadores pelo wrapper. O stdin preserva
a correspondência da linha inteira e evita o pipeline sujeito a SIGPIPE.
Não exercitados: aparelho físico, telas do app e presença durante a queda
de Wi-Fi; esses passos não fazem parte dos critérios deste ticket.

## Prova red-green e gate

Prova independente red: testes atuais contra uma cópia de `qa/phone.ps1`
da base `ab85fe7`, em diretório local isolado. Exit 1; 15 testes, 13 pass e
2 fail (`large-dump` e o pai), zero skips. Falha correta:
`Argument list too long`. `status-denied` e os outros cenários passaram.
O harness exerce a CLI e o script gerado de produção.

Green: `powershell -ExecutionPolicy Bypass -File .\gate.ps1`, em primeiro
plano, exit 0, `gate: passou`. Rust fmt/Clippy e TypeScript passaram;
Rust 83/83, interface 61/61 e kit QA 96/96, sem falhas ou skips.
Android `check`: `BUILD SUCCESSFUL in 14s`, 41 tarefas, uma executada e
40 up-to-date. Os relatórios das tarefas de teste reutilizadas registram
166 testes em 17 suítes, zero falhas, erros ou skips; excluídas as cópias
em `intermediates` da contagem.

## Infraestrutura e evidências

AVD `SynchroNice_API37`, endpoint `127.0.0.1:5555`, SDK físico em
`D:/AppData/Local/Android/Sdk`. O primeiro `up` falhou ao iniciar adb pelo
caminho padrão e executou down; a repetição com `-Sdk` físico saiu 0.
APK debug compilado/instalado; PC debug compilado em 11m22s e aberto com
CDP 9222 e dados isolados nesta execução. O Wi-Fi foi verificado enquanto
o PC compilava. Ao terminar a preparação, `up` confirmou online true;
presença posterior: HTTP 200/online true. `am start -W` reportou timeout,
mas o pareamento e a presença foram confirmados pelo kit.

Evidências locais no diretório homônimo, ignorado pelo git:
`red/red.log`, `gate.log`, `up.log`, `up-retry.log`, `environment.log`,
`wifi-off.log`, `phone-after.log`, `presence-after.log` e `down.log`.
Configuração, QR e tokens permanecem locais.

## Limpeza

Lista vazia de arquivos de usuário criados, enviados ou apagados.
O script de Wi-Fi e seu log foram removidos pelo wrapper; a consulta final
não encontrou `synchronice-qa-wifi-*` em `/data/local/tmp` e confirmou
`Wi-Fi is enabled`. `down` com o mesmo SDK/RunDir saiu 0:
`{"restored":false,"stopped":true}`; estado active false, PC isolado e AVD
encerrados. O PC real não estava aberto antes da preparação.
As evidências locais foram preservadas. A conversão de finais de linha de
`Cargo.toml` pelo build foi revertida após conferir ausência de mudança de
conteúdo. `git diff --check` limpo.

Standards: 1 desvio leve de processo; Spec: 0 achados. Revisão aprovada;
merge local na sessão `sweatshop/2026-10-04-1210`: `37108e9`.
