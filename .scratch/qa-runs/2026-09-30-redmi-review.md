# QA — 2026-09-30 — Redmi — SYN-022 — stage 3

Verdict: Approve

Revisão de `b4ff7b1...279985c`. O Verification autoriza débito para os
critérios dependentes do Redmi quando uma proteção impede o kit. A tentativa
no celular abortou antes de enviar Itens; C.1 e C.2 continuam não verificados.

## Standards

0 violações e 0 smells relevantes. Diff completo, três commits e fontes de
padrões revisados. Mudanças de fonte respeitam Primary files; no Android,
apenas tags e semântica. `7d0028a` contém testes/fixture e transição de etapa;
`cc9430e` e `279985c` não alteram testes. Os wrappers são exigidos pelo ticket.

## Spec

0 achados. Parser, chamada no gate e documentação correspondem aos critérios
1, 2 e 6. Pontos não verificados dos critérios 3–5 e 7 ficam no débito
autorizado. Sem desvio de escopo; pareamento, arquivos, emulador e integração
nos bindings pertencem aos SYN-023–026.

A decisão `Proxy decided` do ticket foi revisada: `WRITE_SECURE_SETTINGS`
é falha de infraestrutura; 👤 e débito cobrem somente o que não foi alcançado,
sem afirmar sucesso dos envios nem alterar a segurança do Redmi.

## Evidência da execução

- `phone.ps1 dump` reconectou automaticamente o adb sem fio. Depois, saiu
  com código 1 na preparação, antes do dump:

```text
infraestrutura adb: Exception occurred while executing 'put':
java.lang.SecurityException: Permission denial, must have one of:
[android.permission.WRITE_SECURE_SETTINGS]
```

- Após a reconexão, `adb devices` mostrou
  `adb-njca85bihiau99t8-Ctn75I._adb-tls-connect._tcp device`.
  O serviço `_adb-tls-connect._tcp` foi descoberto em `192.168.18.9:34431`.
- `phone.ps1 dump -Serial njca85bihiau99t8` saiu com código 1 e
  `sem aparelho: transporte USB recusado`. Não se reproduziu o cenário
  físico com apenas USB.
- `pc.ps1 start` abriu o release com PID 24104 e CDP 9222.
- `pc.ps1 text -Selector body` retornou o DOM, com “Nada aqui ainda” e
  “Enter envia · arraste ou Ctrl+V”. O print confirmou o chat vazio.
- `pc.ps1 invoke -Command list_items` retornou `[]`.
- `pc.ps1 presence` retornou `{"status":204,"online":false}`.
- `pc.ps1 screenshot` gravou
  `2026-09-30-redmi-review/pc.png`, inspecionado localmente e fora do git.

| Passo | Veredito | Evidência em texto |
| --- | --- | --- |
| C.1 — PC → celular | 👤 humano | Redmi nega a preparação com `WRITE_SECURE_SETTINGS`; nenhum envio executado. PC/CDP acessível, chat vazio, `list_items=[]`, presença 204. |
| C.2 — celular → PC | 👤 humano | Mesma negativa antes do dump e do toque no composer; nenhum Item enviado. |

## Prova e gate

Testes/fixture do commit vermelho `7d0028a`, sem `dump.mjs`: 1 arquivo
falhou com `ERR_MODULE_NOT_FOUND`. Diretório temporário removido no fim.
Gate completo após rebase, em primeiro plano: `gate: passou` e
`BUILD SUCCESSFUL`. fmt, clippy, typecheck e lint passaram.

| Suíte | Testes | Resultado |
| --- | --- | --- |
| Rust | 51 | passaram |
| UI | 47 | passaram |
| Parser QA | 5 | passaram |
| Android app | 56 | UP-TO-DATE; XMLs sem falhas, erros ou skips |
| Protocol-client | 62 | UP-TO-DATE; XMLs sem falhas, erros ou skips |

## Limpeza e estado final

Nenhum arquivo enviado para a Pasta ou apagado. Nenhum texto enviado.
PC aberto pelo kit permanece aberto; Par não alterado pelo kit.
Print e stderr ficam em `2026-09-30-redmi-review/`, ignorado pelo git.
Débitos do relatório anterior preservados: cenário físico apenas USB,
resource-ids no dump real, bolha e presença 200, C.1/C.2 completos.
