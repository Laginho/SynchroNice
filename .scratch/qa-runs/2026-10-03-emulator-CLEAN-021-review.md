# QA — 2026-10-03 — Emulador — CLEAN-021

Infraestrutura: AVD `SynchroNice_API37`, adb real em `127.0.0.1:5555`,
SDK `D:/AppData/Local/Android/Sdk`, APK e PC debug da branch `clean-021`
(`7ce04ba`), PC e Par isolados, CDP 9222. Execução local:
`.scratch/qa-runs/2026-10-03-emulator-CLEAN-021-review/`.

O primeiro `up` compilou o PC em 6m39s e iniciou o pareamento, mas não confirmou
presença em 30 s. Foi registrado como infraestrutura; o rollback encerrou os
processos. A retentativa compilou em 4m48s e saiu 0 com `online: true`.
Outra chamada a `pc.ps1 presence -Isolated` confirmou
`{"status":200,"online":true}`. Logs: `up-first-attempt.log`, `up-retry.log`
e `pc.stderr.log`. O PC real estava fechado (`realWasRunning: false`).

O baseline principal foi capturado antes das fixtures e continha 12 caminhos
anteriores. Cada fixture recebeu GUID exclusivo, com ausência confirmada
antes de gravar. São payloads ASCII controlados sob nomes `.jpg`; esta
execução verifica caminhos, manifestos e hashes.

| Critério | Veredito | Evidência |
| --- | --- | --- |
| 1 — proteção no track | ✅ | Com `CLEAN-021-d9c6632514034726be93a29b03213051-Foto.jpg` no baseline filho, `track` da variante minúscula saiu 1 com `So registre arquivos novos desta execucao`; `tracked` vazio e SHA-256 preservado. |
| 2 — proteção no clean | ✅ | Entrada minúscula semeada com hash real: `clean` saiu 1 com `Manifesto inseguro`; arquivo, hash e manifesto preservados. |
| 3 — deduplicação | ✅ | Dois `track`, com grafias original e minúscula de `CLEAN-021-d3bc40d5f50a4b4aa518ff986a86387a-Foto.jpg`, saíram 0 e deixaram uma única entrada. |
| 4 — remoção das variantes | ✅ | Manifesto com original, arquivo alterado e alias: `clean` retirou original e alias de `tracked` antes de parar no alterado, com saída 1 e `Arquivo alterado; preservado`. Persistiu somente o alterado, com conteúdo e hash atuais intactos. |

Evidência local: `phone-criteria-evidence.json`, `phone-files.json`,
`criterion-{1,2,3,4}-{before,after}-manifest.json` e snapshots da limpeza.
Uma comparação final do roteiro local precisou corrigir array aninhado na
leitura de `ConvertFrom-Json`; a rechecagem usou o stdout capturado, sem repetir
ações do telefone. Nenhuma fonte ou teste do repositório mudou.

A limpeza preexistente relatou `Lixeira do MediaStore`, mas uma consulta com
inclusão de itens pendentes encontrou as três fixtures como `.pending-*`,
`is_trashed=0/is_pending=1`. A inferência de Lixeira pelo desaparecimento do
caminho original já existia na base e não muda com os quatro operadores.
Observação registrada em Comments do CLEAN-019 para triagem da hipótese do
seu critério 3. Ela não reprova os critérios do CLEAN-021.

GUID e SHA-256 dos derivados foram conferidos contra as fontes próprias.
Eles foram registrados no manifesto principal e limpos pelo wrapper.
Prefixo de todos os caminhos: `/storage/emulated/0/Download/SynchroNice/`.

| Caminho original | Relato inicial | Destino observado |
| --- | --- | --- |
| `CLEAN-021-d9c6632514034726be93a29b03213051-Foto.jpg` | `Lixeira do MediaStore` | `.pending-1791681919-CLEAN-021-d9c6632514034726be93a29b03213051-Foto.jpg` |
| `CLEAN-021-d3bc40d5f50a4b4aa518ff986a86387a-Foto.jpg` | `Lixeira do MediaStore` no filho; `ja ausente` no principal | `.pending-1791681912-CLEAN-021-d3bc40d5f50a4b4aa518ff986a86387a-Foto.jpg` |
| `CLEAN-021-8239268b0b024ed0a2da82ab45ea8a3e-Changed.jpg` | `Lixeira do MediaStore` | `.pending-1791681922-CLEAN-021-8239268b0b024ed0a2da82ab45ea8a3e-Changed.jpg` |

| Caminho do derivado | Destino final confirmado |
| --- | --- |
| `.pending-1791681919-CLEAN-021-d9c6632514034726be93a29b03213051-Foto.jpg` | `rm (MediaStore indisponivel)` |
| `.pending-1791681912-CLEAN-021-d3bc40d5f50a4b4aa518ff986a86387a-Foto.jpg` | `rm (MediaStore indisponivel)` |
| `.pending-1791681922-CLEAN-021-8239268b0b024ed0a2da82ab45ea8a3e-Changed.jpg` | `rm (MediaStore indisponivel)` |

Resultado final: `clean` saiu 0; `tracked`, `remaining`, `newPaths` e
`baselinePathsMissing` vazios. A listagem contém exatamente os 12 caminhos
anteriores. Nenhum arquivo anterior foi apagado. Evidência em
`cleanupFollowup` do JSON local.

Comandos principais, executados em primeiro plano:

```powershell
powershell -ExecutionPolicy Bypass -File .\gate.ps1
powershell -ExecutionPolicy Bypass -File .\qa\up.ps1 -Sdk 'D:/AppData/Local/Android/Sdk' -RunDir '.scratch/qa-runs/2026-10-03-emulator-CLEAN-021-review'
# up repetido uma vez após a falha de infraestrutura.
# phone.ps1 list-files, track e clean: -Adb, -Serial e -RunDir explícitos.
powershell -ExecutionPolicy Bypass -File .\qa\down.ps1 -Sdk 'D:/AppData/Local/Android/Sdk' -RunDir '.scratch/qa-runs/2026-10-03-emulator-CLEAN-021-review'
```

Gate: saída 0, `gate: passou`; Rust 83/83, interface 61/61 e QA 49/49.
Fmt, clippy e TypeScript passaram. Android: `BUILD SUCCESSFUL` em 15 s,
41 tarefas, 1 executada e 40 up-to-date; testes reutilizados pelo Gradle.
Log: `.scratch/run-log/CLEAN-021-stage3-gate.log`.

Red confirmado no transcript stage 2, linha 26 (`item_12`): quatro cenários
falharam no código original, 0/5 contando o pai. Green na linha 61
(`item_32`): 5/5. Testes chamam a CLI de produção com adb falso e NTFS.

`down` saiu 0 com `{"restored":false,"stopped":true}`; `active: false`,
debug encerrado e adb sem dispositivos. Log: `down.log`.
A alteração de finais de linha em `Cargo.toml` gerada pelo Tauri foi desfeita.
Redmi físico não utilizado; nenhum passo visual ficou pendente.
