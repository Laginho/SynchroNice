# QA — 2026-09-30 — Redmi — SYN-023 — stage 3

Verdict: Approve

Diff revisado: `2eb2c0a...7c726ad`, base
`sweatshop/2026-09-30-2146`. Revisões Standards e Spec feitas em paralelo,
com leitura do contrato e dos caminhos de produção. Nenhuma correção de
código. Merge sem squash na sessão: `96d7d7e`.

## Standards

1 limpeza não bloqueante: `qa/phone.ps1`, ação `pair`, não conserva um check
executável de suas validações, descoberta de IP e escape do JSON. A regra
Ponytail ativa pede um check para lógica não trivial. O harness sintético do
stage 2 foi removido; `DebugPairActivityTest` constrói o JSON diretamente e
não detecta regressões no wrapper. Registrado em CLEAN-018, `needs-triage`.

Esse requisito não consta dos critérios nem dos testes aprovados de SYN-023.
Pelo ticket-flow, requisitos adicionais viram CLEAN; não reabrem o ticket
sem falha de critério, violação Primary/test-first ou regressão demonstrada.
0 achados bloqueantes; 0 smells relevantes. Activity e wrappers são exigidos
pelo contrato e não pedem abstrações adicionais.

Primary files respeitados. `4d12967` contém testes vermelhos e metadados;
`1a2acf8`, `f525fe2`, `37a75ef` e `7c726ad` não alteram testes.

## Spec

0 achados. Critérios 1, 2, 3 e 5 atendidos. A activity usa o AppState da
Application e o `acceptQr` de produção; grava Par e last-address, abre
MainActivity e termina. JSON inválido ou extra ausente não cria Par.
Fonte e declaração ficam em debug. Os manifests merged atuais confirmam
presença em debug e ausência em release.

O wrapper traduz `nome_instancia` do config real para `instance_name` no QR,
usa porta 47820 e o mesmo método de rota do PC, com sobrescrita por `-Ip`.
O roundtrip sintético por adb sem fio e a validação do wrapper estão
registrados no stage 2. Sem escopo extra ou regressão identificada.

Critério 4 permanece 👤, conforme débito autorizado no Verification.
Não há evidência de pareamento, dump com presença ou HEAD 200 no Redmi.

## Evidência da execução

`powershell -ExecutionPolicy Bypass -File .\qa\phone.ps1 pair` saiu 1 na
preparação, antes de ler o config ou iniciar DebugPairActivity:

```text
infraestrutura adb: Exception occurred while executing 'put':
java.lang.SecurityException: Permission denial, must have one of:
[android.permission.WRITE_SECURE_SETTINGS]
```

Computer Use também foi tentado: importação de `@oai/sky` concluída e
`sky.list_windows()` consultado. Nenhuma janela de Redmi, scrcpy, celular
ou emulador estava acessível para verificar o passo visual. Não se alterou
a configuração de segurança do aparelho.

| Critério | Veredito | Evidência |
| --- | --- | --- |
| 1 — JSON válido | ✅ | XML: validQrPersistsThePairAndAddressAndOpensChat passou; Par e endereço reabertos, intent do chat e término conferidos |
| 2 — inválido/ausente | ✅ | XML: invalidQrFinishesWithoutPairingOrOpeningChat e missingQrFinishesWithoutPairingOrOpeningChat passaram |
| 3 — apenas debug | ✅ | Source set debug; manifests merged debug com activity e release sem activity |
| 4 — Redmi após Desparear | 👤 humano | Kit abortou por WRITE_SECURE_SETTINGS antes do pareamento; sem janela acessível do celular no Computer Use |
| 5 — documentação | ✅ | docs/agents/qa.md descreve pair, APK debug, config, IP e verificação de presença |

## Prova e gate

Log JSON original do stage 2: testes committed em `4d12967`,
`gate.ps1 -TestsOnly` saindo 1 em `compileDebugUnitTestKotlin` por
`Unresolved reference 'DebugPairActivity'`, antes de existir a implementação.
Os XMLs verdes atuais registram os três casos sem falhas, erros ou skips.
Nenhum commit posterior altera testes.

Rebase: `Current branch syn-023 is up to date`. Gate completo depois dele,
em primeiro plano, saída 0: `gate: passou` e `BUILD SUCCESSFUL in 10s`.
Fmt, clippy, typecheck e lint passaram.

| Suíte | Testes | Resultado |
| --- | --- | --- |
| Rust | 51 | passaram |
| UI | 47 | passaram |
| Parser QA | 5 | passaram |
| Android app | 59 | UP-TO-DATE; XMLs sem falhas, erros ou skips |
| Protocol-client | 62 | UP-TO-DATE; XMLs sem falhas, erros ou skips |

## Limpeza e estado final

Nenhum arquivo enviado, apagado ou enviado à Lixeira; nenhum Item criado.
Nenhum Par alterado nesta revisão. Débito já registrado do critério 4
preservado; o fluxo permite fechar o ticket com essa verificação pendente.

Standards: 1 limpeza de cobertura, 0 bloqueantes. Spec: 0 achados.
