# QA — 2026-10-04 — Emulador — CLEAN-025 (re-review)

Verdict: Approve

Branch de implementação: `clean-025`, HEAD `674424f`; base da sessão
`sweatshop/2026-10-03-1608` (`fb8c2df`). Re-review do critério 1 reprovado
em `b3cb058` e do diff posterior. Emulador `SynchroNice_API37`, endpoint
`127.0.0.1:5555`, APK debug e PC debug compilados pelo `qa/up.ps1` desta
branch, PC isolado e CDP 9222. SDK de `android/local.properties`:
`D:/AppData/Local/Android/Sdk`. Presença inicial e final: 200, online true.

## Critérios

| Critério | Veredito | Evidência |
| --- | --- | --- |
| CLEAN-025.1 — toque por texto com aspas | ✅ | O composer contém exatamente `say "hi"`, serializado como `text='say "hi"'`, com bounds `[215,1328][789,1475]`. A CLI retorna `{x:502,y:1401}`. `tap -By text -Value 'say "hi"'` sai com código 0; o trace do NativeCommandParameterBinder registra `-s 127.0.0.1:5555 shell input tap 502 1401` e os argumentos separados `502` e `1401`. |
| CLEAN-025.2 — seletor sem aspas | ✅ | O composer contém `CLEAN-025 plain`, com os mesmos bounds. A CLI resolve `(502, 1401)` e o trace de `tap -By text -Value 'CLEAN-025 plain'` confirma essas coordenadas, com código 0. O gate preserva o caso de texto simples com coordenadas `120 230` no fake. |
| CLEAN-025.3 — aspas em type | ✅ | O teste nativo do gate captura exatamente `input text 'say%s"hi"'` e verifica o argv no git-bash. No adb real, `type -Text 'say "hi"'` preserva o texto no composer. Após enviar por `send`, o dump final mostra `bubble-1-outgoing-delivered` e o texto com aspas; o DOM do PC contém o mesmo texto. |
| CLEAN-025.4 — aviso de clipboard | ✅ | As linhas de `paste-image` e `paste-files` em `docs/agents/qa.md` declaram a sobrescrita do clipboard do usuário sem restauração. Conferência documental; o clipboard não precisou ser usado nesta execução. |

O dump final também mostra `bubble-2-outgoing-delivered` com
`CLEAN-025 plain`; o DOM do PC contém ambos os Itens. A primeira leitura
após o envio com aspas ainda tinha `pending`; a leitura posterior confirmou
a entrega, sem repetir o envio.

## Standards

0 violações documentadas; 0 possíveis smells.

- A retomada respeita os Primary files: altera `qa/dump.mjs`,
  `qa/phone.test.mjs` e o ticket.
- A separação teste/produção está preservada: `1a0611f` acrescenta a
  regressão e registra `implementing`; `9c7a758` altera exclusivamente o
  parser; `674424f` registra o handoff. Os pares anteriores
  `88911fb`/`d73bf86` e `c9c561a`/`497c3f0` mantêm essa separação.
- `doubleQuoted` e `singleQuoted` expressam claramente o propósito. A
  correção mantém a lógica localizada e reutiliza `decode`, conforme a
  exigência de simplicidade e coesão das instruções de engenharia.
- A regressão percorre o wrapper e o parser de produção, preservando
  entidades, texto simples e apóstrofos, sem copiar a lógica de produção.

Nenhum novo achado no código anteriormente revisado. Eixo executado por
subagente somente leitura, separado da revisão Spec.

## Spec

0 achados: nenhum requisito ausente/parcial, excesso de escopo ou
implementação incorreta identificado.

O critério 1 está atendido: o parser reconhece atributos com aspas simples
e duplas, preserva a decodificação de entidades e calcula `(502, 1401)`
para os bounds capturados. A nova regressão exercita o wrapper e o parser
de produção, conferindo o `input tap` recebido; o QA real acima confirma
o caso que havia faltado na primeira revisão.

O critério 2 permanece preservado: a alternativa original de atributos
com aspas duplas continua equivalente; os testes mantêm texto simples e
entidades e acrescentam apóstrofo dentro de atributo duplo. Os critérios
3–4 não sofreram mudanças após a revisão anterior.

O revisor examinou `phone.ps1 tap`, CLI posicional/stdin e importação de
`findNode`; seletores id/texto/descrição, atributos vazios, nó ausente,
bounds inválidos e propagação de erro. Nenhuma regressão identificada nos
novos hunks. Eixo executado por outro subagente somente leitura.

## Prova e validação

- A retomada tem teste vermelho em `1a0611f` antes do fix `9c7a758`;
  o vermelho registrado no ticket foi 3 passaram e 2 falharam, incluindo
  o teste pai. O gate desta revisão confirma os casos verdes.
- Comparação adicional sobre o XML real da revisão anterior:
  a CLI de produção em `b3cb058` retorna `null`; a atual retorna
  `{x:502,y:1401}`. Resultado em `parser-before-after.json`.
- `powershell -ExecutionPolicy Bypass -File .\gate.ps1`, em primeiro plano:
  código 0, `gate: passou`; fmt, clippy, typecheck, 83 testes Rust, 61
  testes/subtestes da UI e 88 testes/subtestes QA, todos aprovados.
- `gradle check`: `BUILD SUCCESSFUL`. Testes Android `UP-TO-DATE`;
  os 17 relatórios XML existentes somam 166 testes, zero falhas e erros.
- Rebase sobre a sessão já estava atualizado. Commits de produção não
  alteram testes; diff de implementação limitado aos Primary files e ao
  ticket. `git diff --check` limpo. O build Tauri reescreveu somente finais
  de linha de `Cargo.toml`, sem diff de conteúdo; essa alteração gerada foi
  restaurada antes da integração.

## Evidências e limpeza

Artefatos locais no diretório ignorado
`.scratch/qa-runs/2026-10-04-clean-025-rereview/`:

- `initial.xml`, `quoted-composer.xml`, `plain-composer.xml` e `final.xml`;
- `quoted-tap.trace.log` e `plain-tap.trace.log`, com argv nativo do adb;
- `quoted-success.png`, captura após o toque com aspas;
- `pc-quoted-items.json` e `pc-items.json`, com o DOM do PC;
- `parser-before-after.json` e `dump-before-fix.mjs`, comparação do parser;
- `gate.log`, `up.log` e `down.log`.

`qa/down.ps1` com o mesmo RunDir e SDK terminou com código 0 e
`{"restored":false,"stopped":true}`. A verificação final de `adb devices`
não lista aparelhos/emulador, e não há processo `pc` aberto. Não havia
PC release aberto antes do `up`; não foi necessário restaurá-lo.

Arquivos de usuário criados ou apagados: lista vazia, pois só foram enviados
Itens de texto. Os textos do PC terminam com a Sessão; dados isolados e
evidências locais foram preservados. Sem falha de infraestrutura nesta
execução e sem `Débito humano:` para os critérios.

Standards: 0 achados; Spec: 0 achados — nenhum problema pendente em ambos os eixos.
