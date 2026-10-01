# Kit de QA

O agente lê o roteiro em prosa, chama as primitivas e julga cada passo.
Instale a única dependência com `npm ci --prefix qa`; não baixa navegador.
O PC usa o WebView2 instalado, via [CDP do Playwright](https://playwright.dev/docs/api/class-browsertype#browser-type-connect-over-cdp).

## Redmi: pré-requisitos

O humano faz `adb pair ip:porta` uma vez, na Depuração sem fio do Android.
Deixe celular e PC no mesmo Wi-Fi, tela desbloqueada e celular no carregador,
com Permanecer ativo. O kit acorda a tela e pede `wm dismiss-keyguard`, mas
nunca recebe ou digita PIN. Bloqueio seguro é falha de infraestrutura.
USB é recusado, inclusive quando é o único transporte em `adb devices`.

`phone.ps1` procura um transporte sem fio conectado ou o serviço
`_adb-tls-connect._tcp` de `adb mdns services`, faz `adb connect` e usa sempre
`-s`. Não escolhe serviços de pareamento nem emuladores. Para escolher um
aparelho, passe `-Serial '192.168.18.9:37123'` ou o serial mDNS completo;
`SYNCHRONICE_ADB_SERIAL` também vale. A porta pode mudar: prefira descoberta.
`-Adb` ou `SYNCHRONICE_ADB` indica outro adb; o padrão usa PATH ou o SDK em
`%LOCALAPPDATA%/Android/Sdk/platform-tools/adb.exe`.

Em aparelhos que negam `settings put global` com `WRITE_SECURE_SETTINGS`,
o kit aborta como falha de infraestrutura. A autorização do shell nas
opções de desenvolvedor precisa ser feita pelo humano; o kit não altera
configurações de segurança para contornar a negativa.

Antes das ações, o wrapper zera as três escalas de animação. Um dump com
`could not get idle state` tenta de novo uma vez. Os arquivos temporários
de dump e captura criados pelo kit são removidos depois da leitura.

## Primitivas do celular

Execute da raiz do repo (prefixo de todos os exemplos:
`powershell -ExecutionPolicy Bypass -File .\qa\phone.ps1`):

| Ação | Exemplo de argumentos | Resultado |
| --- | --- | --- |
| Abrir app | `open` | resposta de `am start` |
| Matar app | `stop` | `am force-stop`; encerra a Sessão |
| Tocar por tag | `tap -By id -Value composer` | centro dos bounds no dump; erro se não achado |
| Tocar por texto | `tap -By text -Value Enviar` | busca exata, com entidades XML decodificadas |
| Tocar por descrição | `tap -By description -Value 'Configurações'` | busca exata de `content-desc` |
| Digitar | `type -Text 'QA celular para PC'` | digita no campo em foco |
| Tecla | `key -Key KEYCODE_BACK` | esconde o teclado, por exemplo |
| Árvore | `dump` | XML com textos, descrições, resource-ids e bounds |
| Print | `screenshot -Path '.scratch/qa-runs/2026-09-30-redmi/phone.png'` | PNG local; crie o diretório antes |
| Parear sem câmera | `pair` ou `pair -Ip '192.168.18.12'` | lê o Par do PC, dispara a activity de debug e abre o chat |

`pair` só funciona com o **APK de debug**: `DebugPairActivity` e sua declaração
exportada existem apenas no source set `debug`, nunca no release. O extra
string `qr` recebe o JSON completo e passa pelo mesmo `acceptQr` do scanner;
JSON inválido ou ausente apenas encerra a activity, sem criar Par.
O wrapper lê `instance_name` e `token` de
`%APPDATA%/dev.synchronice.pc/config.json` (`-Config` troca esse caminho),
usa a porta 47820 e o IPv4 escolhido pela rota padrão do PC, como o QR real.
`-Ip` sobrescreve o IPv4, por exemplo quando há VPN. O token não deve ir para
relatórios. O pareamento substitui o Par anterior do celular.
No Redmi, use depois de Desparear pela engrenagem (I.2–I.4); B.2 continua
pela câmera real. Confira o dump com presença `Na rede` e `pc.ps1 presence`
com status 200. Falta de aparelho ou negativa das permissões do shell é
falha de infraestrutura; o atalho não contorna os pré-requisitos do kit.

`adb input text` aceita aqui ASCII imprimível sem `%`. Texto Unicode não é
suportado por essa primitiva e gera erro explícito. Espaços e caracteres de
shell são protegidos; o campo deve estar em foco antes de digitar.

Todo elemento de UI tocado por um critério deve ganhar `testTag`.
A raiz Compose expõe tags como resource-ids com
[`testTagsAsResourceId`](https://developer.android.com/develop/ui/compose/testing/interoperability).
Neste ticket: `composer`, `send`, `presence` e
`bubble-<localId>-<incoming|outgoing>-<pending|delivered|failed>`.
As tags são valores puros, sem prefixo do pacote. A entrega fica na tag da
bolha; o dump também contém o texto e, nas saídas, a legenda Entregue/Pendente.

## Primitivas do PC

Prefixo: `powershell -ExecutionPolicy Bypass -File .\qa\pc.ps1`.
`start` encerra as cópias de `pc.exe` e abre
`pc/src-tauri/target/release/pc.exe`, com a configuração e o Par reais.
Use `-Exe` para outro executável. Encerra a Sessão anterior do PC.
Define `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--remote-debugging-port=9222`
e aguarda o CDP. `-Port` muda essa porta em **todas** as chamadas.
A saída JSON informa PID e arquivo de stderr, dentro de `-RunDir`.
Sem `-RunDir`, cria `.scratch/qa-runs/<data-hora>-redmi/`.
Leia `pc.stderr.log` para diagnosticar falhas do app.

| Ação | Exemplo de argumentos | Resultado |
| --- | --- | --- |
| Abrir | `start -RunDir '.scratch/qa-runs/2026-09-30-redmi'` | PID, porta, stderr |
| Clicar | `click -Selector '.composer .send'` | `null` após clicar |
| Digitar | `type -Selector '#composer-input' -Text 'QA PC para celular'` | substitui o texto do campo |
| Tecla | `key -Selector '#composer-input' -Key Enter` | envia pelo composer |
| Ler DOM | `text -Selector '.item'` | lista dos textos de todas as bolhas |
| Print | `screenshot -Path '.scratch/qa-runs/2026-09-30-redmi/pc.png'` | captura da área web da janela |
| Comando Tauri | `invoke -Command list_items -Json '{}'` | retorno JSON do comando real |
| Presença | `presence` | `{"status":200,"online":true}` ou 204/false |

`invoke` chama `window.__TAURI__.core.invoke`, sem reproduzir a lógica do app.
`presence` faz `HEAD http://127.0.0.1:47820/listen`, com o token lido de
`%APPDATA%/dev.synchronice.pc/config.json`. `-Config` troca esse caminho.
Nunca copie o token para relatórios. Cada ação CDP se desconecta no final;
o PC continua aberto. Seletores devem ser específicos; para várias bolhas,
`text` retorna todas. Prints precisam de diretório existente.

## Execução, retentativa e relatório

Para C.1, abra os dois chats, confira presença, digite no composer do PC e
aperte Enter. Leia as bolhas no DOM e no dump: texto igual nos dois lados,
bolha recebida `incoming-delivered` no celular e saída sem Pendente no PC.
Para C.2, toque `composer`, digite no celular e toque `send`; esconda o
teclado se necessário. Confira o texto no DOM e a bolha `outgoing-delivered`
no dump. `list_items` confirma o Item recebido pelo PC.
Escolha textos identificáveis com o nome e horário da execução.

Um passo instável tenta de novo **uma vez**, antes de ❌. Depois de enviar,
repita a leitura, não o envio: evita duplicar Itens. Um erro de conexão adb
pede uma nova chamada (que reconecta). Queda que não volta, ausência de
aparelho, tela bloqueada ou CDP indisponível abortam a execução como falha de
infraestrutura: não são bug do app nem reprovação do ticket. Marque os passos
não alcançados 👤, descrevendo a causa. Não marque como passou sem evidência.

Um arquivo por execução: `.scratch/qa-runs/<data>-<alvo>.md` (use horário
quando houver mais de uma execução). Commite o Markdown; prints e stderr
ficam no subdiretório homônimo, ignorado pelo git. Exemplo:

```markdown
# QA — 2026-09-30 — Redmi — SYN-022
Infraestrutura: Redmi sem fio, PC release, porta CDP 9222.

| Passo | Veredito | Evidência em texto |
| --- | --- | --- |
| C.1 | ✅ | DOM: QA PC 123; dump: bubble-1-incoming-delivered, texto QA PC 123; PC sem Pendente |
| C.2 | ❌ | Após duas leituras, bubble-2-outgoing-pending; DOM e list_items sem QA celular 123 |

Prints locais: 2026-09-30-redmi/pc.png e phone.png.
Limpeza: nenhum arquivo de usuário criado ou apagado; textos vivem na Sessão.
```

No walkthrough, ❌ abre `BUG-*` com `Status: needs-triage`, sem `Stage:`,
citando passo e ticket de origem. Na revisão de um ticket, ❌ reprova esse
ticket. Um passo verificado aponta o relatório e não gera débito humano.
👤 é o que permanece para o humano; o ticket SYN-022 permite débito nos
critérios 3–5 e 7 se o Redmi não estiver alcançável sem fio.
No fim, liste os arquivos apagados e seu destino. Estas primitivas não
apagam arquivos da Pasta nem despareiam o app; limpeza de arquivos do QA
será implementada no SYN-024. Os textos desaparecem ao encerrar a Sessão.
