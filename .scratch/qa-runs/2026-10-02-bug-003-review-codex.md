# QA — 2026-10-02 — emulador — BUG-003

Status: em andamento; este checkpoint ainda não é o veredito.

Branch: `bug-003`, HEAD inicial `cb81792`. Comparação com a branch de sessão
`sweatshop/2026-10-01-2019` (`a1d417a`, merge-base `608165d`).
Dados e evidências locais: `2026-10-02-bug-003-review-codex/`.

## Validação automática

`powershell -ExecutionPolicy Bypass -File .\gate.ps1`: código 0,
`gate: passou`. PC 56/56 (4 unitários, 7 configuração, 4 outbox,
41 servidor), UI 47/47, kit 14/14; fmt, Clippy e typecheck verdes.
`gradle check` passou; testes Android reutilizados como `UP-TO-DATE`.
Saída integral local: `gate.log`.

A primeira execução falhou porque o PC debug da revisão anterior (PID 6272)
impedia substituir `target/debug/pc.exe`. A execução anterior usava
`2026-10-01-bug-003-review`. `down` precisou de `-Sdk D:/AppData/Local/Android/Sdk`;
o emulador não encerrou após `emu kill`. Foram encerrados somente os processos
daquela execução, identificados por PID, caminho e AVD (`SynchroNice_API37`),
e `down` finalmente retornou `stopped=true, restored=false`.
Essas falhas de infraestrutura não reprovam o ticket.

## Achados a reproduzir

- Standards: se `Outbox::new` falha, `list_sent_items` rejeita o `Promise.all`
  em `main.ts`, antes de ligar composer, drop, colagem e configurações. Na
  base, a carga de recebidos independe de `Outbox` e esses controles são ligados.
- Spec, critérios 3 e 7: `Outbox::new` e `Inner::drop` removem a pasta
  compartilhada `outbox/session-files`. Outra instância pode apagar a cópia
  de um print de uma Sessão ainda viva. Precisa de teste de filas sobrepostas.

Critérios 6–8 e os dois achados aguardam reprodução no aplicativo compilado.
Arrastar para fora da janela aguarda tentativa de Computer Use.

## Limpeza

Nova execução ainda não iniciou; nenhum arquivo de usuário criado ou apagado.
