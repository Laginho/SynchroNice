# QA — 2026-10-03 — Emulador — CLEAN-022

Infraestrutura: AVD `SynchroNice_API37`, adb em
`D:/AppData/Local/Android/Sdk/platform-tools/adb.exe`, endpoint
`127.0.0.1:5555`, PC debug da branch `clean-022`, CDP 9222 e dados isolados
em `2026-10-03-emulator-CLEAN-022-review/data`. O `up` terminou com
`online: true`; `pc.ps1 presence -Isolated` devolveu HTTP 200.

| Critério | Veredito | Evidência em texto |
| --- | --- | --- |
| 1 | ✅ | O adb real listou `127.0.0.1:5555 device` e `emulator-5554 device`, sem serviços mDNS. O wrapper real, sem serial por argumento ou ambiente, saiu 1 com `sem aparelho`. Uma guarda delegou somente `devices`/`mdns services` ao adb real e registraria qualquer outra chamada: zero conexões ou ações. O teste com apenas o endpoint loopback também passou. |
| 2 | ✅ | Teste da CLI real com adb controlado: ao listar emulador antes de `192.168.0.50:41234`, o primeiro comando foi `connect 192.168.0.50:41234`; todas as ações usaram esse serial com `-s`. O fallback mDNS também escolheu o celular após descartar loopback. Nenhum celular físico foi acionado. |
| 3 | ✅ | CLI real com adb controlado descartou `localhost:5555`, `[::1]:5555`, `127.0.0.1:5555` e `127.42.3.4:5555`. Saída 1, `sem aparelho`, sem conexão ou ação. A listagem mDNS contendo somente esses endpoints também foi recusada. |
| 4 | ✅ | `up` pareou o emulador usando serial explícito. `phone.ps1 dump -Serial 127.0.0.1:5555 -Adb <SDK>/platform-tools/adb.exe` saiu 0. Na segunda leitura, XML com um `composer` e `presence` com texto `Na rede`. Os testes anteriores permaneceram intactos e passaram no gate. |

Prova independente red/green: os testes atuais contra o wrapper original de
`205c76c` tiveram 3/11 passados e 8/11 falhos: sete cenários de loopback e
o teste pai. Contra a implementação revisada, 11/11 passaram, incluindo os
dez cenários. O teste chama a CLI de produção; não reproduz sua lógica.
Log red local: `2026-10-03-emulator-CLEAN-022-review/red/red.log`.

Gate completo em primeiro plano: saída 0, `gate: passou`; Rust 83/83,
interface 61/61 e QA 60/60. Formatação, Clippy e TypeScript passaram.
Android: `BUILD SUCCESSFUL`, 41 tarefas, uma executada e 40 up-to-date;
testes reutilizados pelo Gradle. Log:
`.scratch/run-log/CLEAN-022-stage3-gate.log`.

Infraestrutura e retentativas: o primeiro `up` falhou ao executar o adb do
SDK padrão em C: e encerrou sua execução. A repetição usou o SDK em D:,
conforme `android/local.properties`, e passou. O primeiro dump mostrou
`System UI isn't responding`, embora a presença do app fosse HTTP 200.
Após tocar `android:id/aerr_wait`, uma nova leitura mostrou o chat e
`Na rede`, sem o aviso. Não houve falha de critério nem débito humano.

Evidências locais: `2026-10-03-emulator-CLEAN-022-review/phone.xml`,
`phone-retry.xml`, `discovery.stderr.log` e `discovery-device-calls.log`;
logs de `up` e `down` em `.scratch/run-log/CLEAN-022-stage3-*.log`.
Configuração, QR, dumps e guarda de adb ficam no diretório local ignorado;
nenhum token foi incluído neste relatório.

Limpeza: lista vazia de arquivos de usuário criados, enviados ou apagados.
`down` com o mesmo SDK e RunDir saiu 0, `stopped: true`, `restored: false`;
o PC real não estava aberto antes do `up`. O estado ficou `active: false`,
o PC debug foi encerrado e o adb ficou sem dispositivos. Dados e evidências
isolados permanecem locais, como previsto pelo kit. O build alterou somente
finais de linha de `Cargo.toml`; o arquivo foi restaurado após conferir que
não havia mudança de conteúdo.
