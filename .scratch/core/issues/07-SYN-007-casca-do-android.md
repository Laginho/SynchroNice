# SYN-007: Casca do Android
Stage: to-implement
Status: ready-for-agent
Blocked by: SYN-005
Review: agent

- Primary files:
  - New: android/** (projeto Gradle Kotlin: app com chat, módulo cliente do protocolo)
  - gate.ps1 (incluir build e testes JVM do Android)
  - .gitignore (artefatos do Gradle)

#### What to build

O app Android existe. Sem Par, abre a câmera para escanear o QR do PC e grava
o Par. Com Par, mostra o chat vazio no estilo do PC, com indicador de presença.
Encontra o PC resolvendo o nome de instância por mDNS; se falhar, tenta o
último IP conhecido. Mantém a conexão de escuta enquanto o app está em tela e
mostra Itens de texto vindos do PC como bolhas à esquerda. Há um composer com
botão de enviar que manda texto ao PC (bolha à direita, Pendente até o PC
confirmar). O módulo cliente do protocolo é separado da interface e testável
na JVM.

#### Acceptance criteria

1. Primeiro uso: escanear o QR do PC grava o Par; reabrir o app vai direto para o chat.
2. Com PC e celular na mesma Wi-Fi, o app encontra o PC sem digitar nada e o indicador mostra "na rede".
3. Trocar o IP do PC (reconectar o Wi-Fi) e o celular volta a encontrá-lo sem reescanear.
4. Um texto enviado do PC aparece como bolha à esquerda no celular.
5. Um texto digitado no celular aparece no PC e a bolha à direita passa de Pendente a Entregue.
6. Com o PC fora da rede, o texto digitado fica Pendente e é entregue quando o PC volta.
7. `gate.ps1` roda os testes JVM do módulo cliente e falha se eles falharem.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 1 a 6: manual, com PC real.

## Tests stage 2 writes (own commit, red)

- `android/.../test/ProtocolClientTest.kt` contra um servidor falso local: enviar texto com token faz a requisição certa; escuta recebe Itens em ordem; servidor indisponível deixa o Item Pendente e ele é enviado ao reconectar; resolução falhando usa o último IP conhecido. Vermelho porque o módulo cliente não existe.

## Comments
