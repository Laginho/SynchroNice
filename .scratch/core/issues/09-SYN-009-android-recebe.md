# SYN-009: Android recebe arquivos do PC
Stage: implementing
Status: ready-for-agent
Blocked by: SYN-007, SYN-004
Review: agent

- Primary files:
  - android/protocol-client/** (download de Item de arquivo pelo cliente do protocolo: bytes por `GET /outbox/{id}/file` com progresso, `ack` só depois de íntegro)

#### What to build

**Escopo desta entrega (decisão de 2026-09-20, ver `## Comments`):** só o
download de Item de arquivo no módulo cliente do protocolo
(`android/protocol-client`, Kotlin/JVM puro), sem
`com.android.application`. É a parte do ticket que compila e é testável com
o JDK 17 já instalado, é a única costura que "Tests stage 2 writes" nomeia,
e é o que o critério 7 cobre. Nada de bolha, miniatura, cache, salvar na
galeria ou em Downloads, nem abrir navegador nesta rodada.

O módulo cliente ganha o download: quando o `/listen` anuncia um Item de
arquivo, o cliente baixa os bytes por `GET /outbox/{id}/file` em stream,
sem carregar o arquivo inteiro na memória, emitindo progresso crescente que
termina em 100, e entrega bytes idênticos aos que saíram do PC. Download
interrompido não marca o Item como completo: o `POST /outbox/{id}/ack` só
sai depois dos bytes íntegros, para o PC reentregar o que não chegou (ver os
dois comentários da revisão de SYN-004 em `## Comments`). É essa costura que
a casca do app vai consumir para a bolha com progresso e a ação de salvar,
quando existir.

#### Adiado — precisa do Android SDK

O que segue é a recepção de arquivo no app de fato e sai do escopo desta
entrega. Vira ticket próprio quando o Android SDK existir numa máquina de
build.

Itens de arquivo mandados do PC aparecem no chat do celular como bolha à
esquerda. Imagem mostra miniatura carregada do cache do app, sem ir para a
galeria. Outro tipo mostra nome, extensão e tamanho. Durante o download a
bolha mostra progresso animado. Um toque na bolha salva: imagem vai para a
galeria, outro arquivo para Downloads, e a bolha indica que foi salvo. Um
toque em texto que é link abre o navegador.

Critérios adiados:

1. Uma imagem enviada do PC aparece como miniatura no celular sem aparecer na galeria.
2. Um toque na miniatura salva a imagem na galeria e a bolha indica "salvo".
3. Um PDF enviado do PC aparece com nome, `PDF` e tamanho; um toque o salva em Downloads.
4. Durante o download de um arquivo grande a bolha mostra progresso animado.
5. Um link enviado do PC abre no navegador ao tocar.
6. Fechar o app descarta as bolhas; o que foi salvo permanece na galeria ou em Downloads.

#### Acceptance criteria

7. `gate.ps1` roda os testes JVM do módulo cliente para o download de Item de arquivo (anúncio recebido pelo `/listen` baixa pela rota de download com progresso crescente até 100 e entrega bytes idênticos; download interrompido não marca o Item como completo nem envia o `ack`), e falha se eles falharem.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 1 a 6: adiados (bloco acima), sem verificação manual nesta entrega.

## Tests stage 2 writes (own commit, red)

- `android/.../test/ProtocolClientTest.kt`: ao receber anúncio de Item de arquivo, o cliente baixa pela rota de download com progresso crescente e entrega bytes idênticos; download interrompido não marca o Item como completo. Vermelho porque o download não existe.

## Comments

- 2026-09-20 (revisão de SYN-004): **a cadência de reconexão do celular é o
  que decide o indicador de presença do PC, e ela é deste ticket.** O
  `GET /listen` do PC (`pc/src-tauri/src/server.rs`) é long-poll que fecha
  depois de entregar: bloqueia até haver ao menos um Pendente, devolve todos
  os de agora (um JSON por linha) e encerra. A presença do PC é a contagem de
  conexões `/listen` vivas, então **cada entrega derruba a presença até o
  celular reabrir** — o ponto verde do PC pisca a cada envio. Se este cliente
  reabrir o `/listen` imediatamente depois de ler a resposta, o piscar fica
  imperceptível; se esperar, o critério 8 do SYN-004 fica feio na prática
  mesmo estando satisfeito na letra. O simétrico também é deste ticket: um
  celular que some sem fechar o TCP deixa o contador do PC em 1 e a presença
  mentindo "na rede" até o socket estourar, porque o handler está só
  aguardando o próximo Pendente. Vale um keep-alive ou um timeout curto no
  lado do cliente.
- 2026-09-20 (revisão de SYN-004): o protocolo que este cliente consome é
  `GET /listen` (anúncio), `GET /outbox/{id}/file` (bytes) e
  `POST /outbox/{id}/ack` (confirmação) — **só o `ack` remove o Item da fila
  de Pendentes do PC**, nunca a entrega em si, e o `ack` é idempotente (204
  mesmo para id que já saiu). Reenviar a confirmação é inofensivo; deixar de
  enviá-la faz o PC reentregar o mesmo Item, então o cliente precisa ignorar
  id já recebido (spec, "Protocolo de Itens").

- 2026-09-20 Resposta (humano), antes do stage 2: **opção 2, a mesma de
  SYN-007 e SYN-008.** Este ticket bate na mesma parede antes de começar, então
  decido agora em vez de deixar o stage 2 parar em `blocked` pela terceira vez
  com a mesma pergunta. Tudo que o corpo descreve — bolha de arquivo à
  esquerda, miniatura do cache, progresso animado, toque para salvar na
  galeria ou em Downloads, toque em link abrindo o navegador — é app Android
  de verdade: exige `com.android.application`, que exige o Android SDK, e
  esta máquina continua sem ele (sem `ANDROID_HOME`/`ANDROID_SDK_ROOT`, sem
  `android/local.properties`). Não instale o Android SDK: a decisão
  registrada em SYN-007 e repetida em SYN-008 continua valendo — 1–2 GB de
  SDK é mudança na máquina, não no repo, e fica com o humano de verdade. O
  que este ticket tem de testável sem SDK é o módulo cliente: receber pelo
  `/listen` o anúncio de um Item de arquivo, baixar os bytes por
  `GET /outbox/{id}/file` com progresso crescente e entregá-los íntegros, e
  não marcar como completo (não mandar o `ack`) um download interrompido —
  exatamente o que "Tests stage 2 writes" já nomeia. Escopo reduzido a isso +
  critério 7; critérios 1–6 (bolhas, miniaturas, salvar, abrir link) movidos
  para o bloco "Adiado — precisa do Android SDK" no corpo, numeração original
  mantida, igual a SYN-007 e SYN-008. Sem ticket novo por enquanto: o bloco
  Adiado é o registro, e o ticket próprio nasce quando houver SDK numa máquina
  de build, junto com a casca de SYN-007 e o Compartilhar de SYN-008. Os dois
  comentários da revisão de SYN-004 acima seguem valendo para quem
  implementar; o que deles é da costura desta entrega é o `ack` só depois dos
  bytes íntegros — a cadência de reabrir o `/listen` é casca do app (adiada,
  como a nota do stage 2 de SYN-007 já registrou). `Stage` segue
  `to-implement`: o ticket nunca bloqueou. Quem pegar o stage 2: não toque em
  `android/settings.gradle.kts` nem declare `com.android.application` sem
  SDK, pelo motivo já registrado em SYN-008; nada fora de
  `android/protocol-client/` e deste ticket.
- Débito humano: recepção de arquivo no app Android (critérios 1–6: bolha de arquivo à esquerda com progresso animado, miniatura de imagem do cache do app sem ir para a galeria, nome/extensão/tamanho para outros tipos, toque que salva na galeria ou em Downloads e indica "salvo", toque em link abrindo o navegador, bolhas descartadas ao fechar o app) não foi construída nem verificada à mão — depende do Android SDK numa máquina de build; decidir se instala e abrir ticket para ela, junto com a casca de SYN-007 e o Compartilhar de SYN-008.
