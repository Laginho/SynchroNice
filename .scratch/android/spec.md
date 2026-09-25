# Spec: App Android, Copiáveis e Engrenagem

Status: ready-for-agent

Fonte: grilling de 2026-09-23 (5 rodadas), sobre a spec original
`.scratch/core/spec.md`. Vocabulário: `CONTEXT.md`. Decisões:
`docs/adr/0001`, `docs/adr/0002`, `docs/DECISIONS.md`.

Esta spec **complementa** a original. Onde as duas divergem, esta vence: Pasta
com padrão em vez de diálogo no primeiro uso, arquivo recebido no celular
gravado direto em vez de "um toque salva", e o celular só recebendo com o app
aberto.

## Problem Statement

O PC está pronto e verificado à mão, mas o celular não tem app: só o módulo
cliente do protocolo existe, testado na JVM. Sem o app, o celular não pareia,
não compartilha nada e não recebe nada. O "Compartilhar" do Android, que é o
motivo do projeto existir, não existe.

Duas dores apareceram no caminho. Primeiro, a Pasta do PC é escolhida num
diálogo que abre sem aviso no primeiro uso, e nenhum dos dois aparelhos deixa
trocá-la depois. Segundo, copiar um pedaço de um texto recebido (um CPF, um
telefone, uma chave PIX) exige selecionar à mão. O WhatsApp tenta resolver
isso e erra: trata qualquer número como telefone e um toque disca.

## Solution

Um app Android que abre direto na câmera no primeiro uso, pareia com o QR do
PC e depois vai sempre para o chat. O chat é o mesmo do PC: bolhas, "eu" à
direita, indicador de presença. "Compartilhar" de qualquer app manda direto
para o PC, sem abrir o chat e sem confirmação. Com o PC fora da rede, o Item
fica Pendente e o Android tenta de novo sozinho em segundo plano. Arquivos
recebidos são gravados em `Download/SynchroNice` assim que chegam. Imagem abre
num visualizador dentro do app, e o resto abre no app padrão. Um botão de
câmera ao lado do campo de texto tira uma foto e a manda.

Nos dois aparelhos, uma engrenagem discreta abre as configurações: a Pasta, o
Desparear e as opções. No texto das bolhas, CPF, CNPJ, telefone, e-mail e
chave PIX aleatória aparecem destacados, e um toque copia só aquilo. Só
formatos que se validam são destacados, então um "150" no meio da frase nunca
vira nada.

Nenhuma notificação do app. A única que existe é a que o Android exige
enquanto dura um envio longo.

## User Stories

### Pareamento no celular
1. Como usuário, quero que o app abra direto na câmera no primeiro uso, para parear sem passar por uma tela vazia.
2. Como usuário, quero escanear o QR do PC uma vez e depois o app abrir sempre no chat, para nunca mais pensar em pareamento.
3. Como usuário, quero que o celular encontre o PC sozinho na mesma Wi-Fi, mesmo que o IP do PC mude, para não digitar nada.
4. Como usuário, quero ver no topo do chat se o PC está na rede, para saber por que algo está Pendente.
5. Como usuário, quero um botão "Desparear" na engrenagem do celular, para trocar de PC.
6. Como usuário, quero que, se o PC recusar o celular (porque pareou de novo com outro aparelho), o app desfaça o Par e abra a câmera sozinho, para não ficar tentando conectar para sempre sem entender.
7. Como usuário, quero que desparear descarte os Pendentes, para nada destinado ao PC antigo ir parar num PC novo.

### Chat no celular
8. Como usuário, quero digitar um texto e tocar em enviar, para mandar uma anotação rápida ao PC.
9. Como usuário, quero ver os textos vindos do PC como bolhas à esquerda, e os meus à direita.
10. Como usuário, quero ver na bolha se o Item está Pendente ou Entregue, para saber se foi sem precisar de notificação.
11. Como usuário, quero que o chat vazio diga o que fazer, para não precisar de tutorial.
12. Como usuário, quero que o celular receba Itens do PC enquanto o app está aberto, e que os Itens mandados com o app fechado esperem Pendentes no PC até eu abrir o app.
13. Como usuário, quero que o chat se descarte quando o Android matar o app, e que os arquivos na Pasta fiquem.

### Enviar do celular
14. Como usuário, quero compartilhar uma foto da galeria para o SynchroNice e ela chegar no PC sem eu abrir o app.
15. Como usuário, quero compartilhar texto ou link de qualquer app e ele aparecer no chat do PC.
16. Como usuário, quero compartilhar qualquer arquivo (PDF, vídeo, áudio) e ele chegar na Pasta do PC.
17. Como usuário, quero compartilhar várias fotos de uma vez e vê-las como Itens separados, para arrastar cada uma no PC.
18. Como usuário, quero que compartilhar não peça confirmação nem abra o chat, para voltar ao que eu estava fazendo.
19. Como usuário, quero que um Compartilhar com o PC fora da rede fique Pendente e seja entregue sozinho quando o PC voltar, mesmo com o app fechado.
20. Como usuário, quero que um Pendente não gere notificação, para o celular não me incomodar com isso.
21. Como usuário, quero que um arquivo grande compartilhado continue indo mesmo se eu sair do app, aceitando a notificação de progresso que o Android exige enquanto ele vai.
22. Como usuário, quero abrir o app e ver como bolhas o que compartilhei, com o estado de cada uma.
23. Como usuário, quero um botão de câmera ao lado do campo de texto, para tirar uma foto e mandá-la ao PC na hora.
24. Como usuário, quero que confirmar a foto na câmera já envie, sem uma segunda tela.
25. Como usuário, quero escolher na engrenagem se as fotos tiradas pelo app ficam guardadas no celular, sem guardar por padrão, porque normalmente a foto é de passagem.
26. Como usuário, quero que o celular não tenha botão de anexar, porque o Compartilhar já cobre isso.

### Receber no celular
27. Como usuário, quero que arquivos recebidos sejam gravados em `Download/SynchroNice` assim que chegam, para não precisar de um toque para salvar.
28. Como usuário, quero escolher outra pasta na engrenagem do celular.
29. Como usuário, quero ver miniatura de imagem recebida e nome, tipo e tamanho dos outros arquivos na bolha.
30. Como usuário, quero ver progresso animado na bolha de um arquivo grande que está chegando.
31. Como usuário, quero tocar numa imagem e vê-la em tela cheia dentro do app, com pinça para zoom, sem abrir a galeria.
32. Como usuário, quero tocar num PDF ou em outro arquivo e ele abrir no app padrão do celular.
33. Como usuário, quero que um nome repetido na pasta não sobrescreva o arquivo anterior.

### Pasta e engrenagem no PC
34. Como usuário, quero que o PC grave arquivos em `Downloads\SynchroNice` desde o primeiro uso, sem um diálogo aparecer do nada.
35. Como usuário, quero uma engrenagem no cabeçalho da janela do PC que abra as configurações.
36. Como usuário, quero trocar a Pasta do PC na engrenagem, e que o próximo arquivo já vá para a nova.
37. Como usuário, quero um botão "Desparear" na engrenagem do PC, que mostre um QR novo e faça o celular antigo parar de conectar.
38. Como usuário, quero que a bandeja continue só com "Abrir" e "Sair".

### Copiáveis (PC e celular)
39. Como usuário, quero ver destacados na bolha os CPFs, CNPJs, telefones, e-mails e chaves PIX aleatórias, para saber o que dá para copiar com um toque.
40. Como usuário, quero que um número qualquer ("150", um item de lista, um ano) nunca seja destacado, ao contrário do WhatsApp.
41. Como usuário, quero que um CPF ou CNPJ só seja destacado se os dígitos verificadores forem válidos.
42. Como usuário, quero que CPF, CNPJ e telefone apareçam formatados na bolha mesmo que tenham vindo sem pontuação.
43. Como usuário, quero que tocar num copiável copie só os dígitos, porque cadastros costumam aceitar só números.
44. Como usuário, quero uma opção "Copiar com pontuação" na engrenagem, desligada por padrão, para quando eu precisar da pontuação.
45. Como usuário, quero que tocar num link abra o navegador, e que tocar no resto da bolha copie o texto inteiro.
46. Como usuário, quero que o toque longo num link copie o link no celular.
47. Como usuário, quero que o link seja azul sublinhado e o copiável azul sem sublinhado, para saber antes de tocar se aquilo abre ou copia.
48. Como usuário, quero o mesmo comportamento no PC e no celular, e que no PC arrastar o mouse continue selecionando um trecho do texto.

## Implementation Decisions

### Copiáveis
- **Só formatos que se validam.** A lista é fechada:
  - **CPF**: 11 dígitos, com ou sem pontuação, e os dois dígitos verificadores válidos.
  - **CNPJ**: 14 dígitos, com ou sem pontuação, e os dígitos verificadores válidos.
  - **Celular**: DDD válido, depois `9` e mais 8 dígitos (11 no total), com `+55` opcional.
  - **Fixo**: DDD válido, depois 8 dígitos começando com 2 a 5 (10 no total), com `+55` opcional.
  - **E-mail**.
  - **Chave PIX aleatória** (UUID).
  - **Link**, que continua sendo link: abre, não copia.

  Nenhuma outra sequência de dígitos é destacada. Um código de boleto ou de
  protocolo se copia tocando na bolha, que copia o texto inteiro.
- **DDD válido** é a lista de DDDs em uso da Anatel, fixa no código. Não é uma
  faixa numérica.
- **Ambiguidade entre CPF e celular.** Quando 11 dígitos passam nos dois, o
  celular ganha. Os dígitos copiados são os mesmos, então só a formatação
  exibida muda. A colisão exige DDD válido, `9` no terceiro dígito e dígitos
  verificadores de CPF válidos ao mesmo tempo, e é rara.
- **Limites.** Um copiável não pode estar colado a letras ou a outros dígitos:
  `12345678909123` não contém um CPF.
- **Exibição e cópia.** CPF, CNPJ e telefone aparecem formatados
  (`123.456.789-09`, `12.345.678/0001-95`, `(11) 91234-5678`, `(11) 3456-7890`).
  Por padrão, tocar copia só os dígitos. Com "Copiar com pontuação" ligado,
  copia a forma formatada. E-mail e chave aleatória são copiados como estão.
- **Uma função pura por app.** O mesmo contrato em Kotlin e em TS: recebe o
  texto e devolve a sequência de trechos. Cada trecho é texto comum, link ou
  copiável, e o copiável traz o tipo, o texto exibido e o texto copiado nos
  dois modos. A renderização da bolha consome essa sequência e não sabe nada de
  regex.
- **Casos compartilhados.** Um único arquivo de casos na raiz do repositório,
  com a entrada e os trechos esperados, é lido pelos testes dos dois apps. É o
  que faz "igual nos dois" ser verdade, e um caso novo entra nos dois de uma vez.
- **Toques.** Link: abre o navegador. Copiável: copia. Resto da bolha: copia o
  texto inteiro. No celular, o toque longo no link copia o link. No PC,
  arrastar o mouse seleciona como hoje. Só um clique sem movimento copia.
- **Aparência.** Os dois tipos usam o azul de destaque da paleta existente. O
  link é sublinhado e o copiável não.

### Engrenagem
- **PC.** Um ícone de engrenagem no cabeçalho da janela abre um painel com
  "Mudar pasta…", que abre o diálogo nativo de pasta e vale na hora, igual ao
  caminho que já atualiza o servidor em execução, e com "Desparear", que é o
  re-pareamento que já existe: token novo, Pendentes descartados, QR novo. Tem
  também "Copiar com pontuação". A bandeja não muda.
- **Celular.** A mesma engrenagem no topo do chat, com "Mudar pasta…" (seletor
  de pasta nativo do Android), "Desparear", "Copiar com pontuação" e "Guardar
  fotos tiradas". As opções ficam nas preferências do app, fora do Par.
- **Grupo, no futuro, substitui o Desparear** por um painel de participantes.
  Nada disso é construído agora.

### Pasta
- **Padrão nos dois aparelhos.** `Downloads\SynchroNice` no PC,
  `Download/SynchroNice` no celular. A pasta é criada quando precisa. O
  diálogo de Pasta do primeiro uso no PC deixa de existir.
- **Celular: gravar ao chegar.** Todo arquivo recebido vai para a Pasta assim
  que o download termina, imagem inclusive. No padrão, a gravação passa pelo
  MediaStore: sem permissão e já aparece na galeria como álbum. Numa pasta
  escolhida pelo usuário, passa pelo acesso a pastas do Android (Storage Access
  Framework). Nome repetido recebe sufixo, como no PC.
- **Mínimo Android 10**, o primeiro que grava em `Download/` sem pedir permissão.

### App Android
- **Módulo do app** ao lado do módulo cliente do protocolo, que continua JVM
  puro e é consumido pelo app. Compose, Activity única.
- **Primeiro uso**: sem Par, a Activity abre na câmera (CameraX com
  decodificação ZXing, sem Play Services). O QR lido vira o Par, e o chat abre.
- **Descoberta**: `NsdManager` implementa o resolvedor de nome de instância
  do módulo cliente. O último IP conhecido fica nas preferências. O tráfego
  HTTP sem cifra é liberado na configuração de segurança de rede do app
  (ADR-0002).
- **Escuta só com o app em tela.** A conexão de escuta abre quando o chat
  aparece e fecha quando sai. A presença é essa conexão.
- **Token recusado** (HTTP 401 do PC) tem o mesmo efeito do Desparear: descarta
  o Par e os Pendentes e abre a câmera. HTTP 503 continua sendo "tentar de
  novo".
- **Enviar.** Todo envio, seja do Compartilhar, da câmera ou do texto, entra
  na fila de Pendentes do módulo cliente antes de sair. O arquivo é copiado para
  a pasta interna do app antes, porque a URI do Compartilhar não sobrevive (spec
  original). O envio roda como trabalho do WorkManager: acelerado quando é
  curto, e em primeiro plano com a notificação de progresso quando é longo.
  Falhou por rede ou 503: o WorkManager tenta de novo com a restrição de rede
  conectada. Nenhuma notificação além dessa obrigatória.
- **Compartilhar.** Filtros de intent SEND e SEND_MULTIPLE, para texto e
  qualquer mime. A Activity de entrada enfileira cada Item e fecha na hora, sem
  interface.
- **O chat mostra** os Itens desta vida do processo e, sempre, os Pendentes da
  fila, mesmo que tenham vindo de um Compartilhar com o app fechado.
- **Receber.** Cada Item anunciado na escuta é baixado para a Pasta, com
  progresso na bolha, e confirmado ao PC depois de gravado. O texto é confirmado
  ao aparecer. O módulo cliente passa a aceitar um destino de download em
  stream, porque o MediaStore e o seletor de pastas não dão um arquivo comum.
- **Visualizador** é uma tela cheia dentro do app, com pinça para zoom e
  "voltar" para fechar. Os outros tipos abrem com a intent padrão de visualizar.
- **Câmera do composer** usa a câmera do sistema (contrato de tirar foto). O
  "✓" da câmera é o envio. Com "Guardar fotos tiradas" ligado, a foto também é
  gravada na Pasta. Só foto, sem vídeo.

## Testing Decisions

- **Um teste bom exercita comportamento externo**: o que chega na rede, o que
  fica em disco, os trechos que a detecção devolve. Nunca funções internas.
- **Costura 1, o protocolo HTTP do PC** (existente, testes de integração em
  Rust e testes de configuração). Cobre a Pasta padrão quando a configuração não
  tem uma, a troca de Pasta valendo no servidor em execução, e o Desparear
  (rotação do token já coberta: estender só no que mudar).
- **Costura 2, o cliente do protocolo no Android** (existente, testes JVM
  contra um servidor falso). Cobre o download para um destino em stream e o 401
  como sinal distinto de "descartar o Par".
- **Costura 3, a detecção de copiáveis** (nova, uma por app). No Android, testes
  unitários locais do módulo do app, na JVM, sem emulador. No PC, um módulo TS
  testado pelo executor de testes nativo do Node, que o `gate.ps1` passa a
  rodar. Os dois leem o mesmo arquivo de casos. Os casos cobrem, no mínimo:
  número comum não destacado, CPF válido e inválido, CNPJ válido e inválido,
  celular com e sem `+55` e pontuação, fixo, colisão CPF × celular, limites
  colados a letras e dígitos, e-mail, UUID, link com número dentro, e os dois
  modos de cópia.
- **Fora de teste automático**: interface, câmera, leitor de QR, mDNS real,
  Compartilhar, WorkManager no aparelho, MediaStore e seletor de pastas,
  visualizador e engrenagem. Cada ticket traz a verificação manual por critério.
- **Prior art**: `pc/src-tauri/tests/server.rs` e `tests/config.rs` no PC,
  `ProtocolClientTest` no Android. A costura 3 não tem prior art: o primeiro
  ticket de cada lado define o padrão.

## Out of Scope

- **Grupo**: mais de dois aparelhos, inclusive PC para PC. É o próximo passo
  depois do Android: o Par vira um conjunto, e a engrenagem ganha um painel de
  participantes no lugar do Desparear. Não é armazenamento permanente, e não é
  um Syncthing: continua sendo passar um Item e esquecer.
- Receber no celular com o app fechado (escuta em segundo plano).
- Qualquer notificação do app, inclusive de Item recebido ou Pendente.
- Botão de anexar no celular e envio de vídeo pela câmera do app.
- Visualizador embutido para tipos que não sejam imagem.
- Qualquer copiável fora da lista fechada, e qualquer ação além de copiar
  (discar, abrir app de banco).
- Tudo que já estava fora de escopo na spec original.

## Further Notes

- **Build Android.** O SDK desta máquina fica em `D:\AppData\Local\Android\Sdk`
  (command-line tools, `platform-tools`, `platforms/android-37.0`), apontado
  por `android/local.properties` (fora do git). O AGP que aceita a API 37
  (9.2) exige Gradle 9.4.1 e traz o Kotlin 2.2.10, então o módulo cliente sobe
  junto. O JDK 17 continua. O `gradlew check` com um módulo Android inclui o
  lint do Android, que é lento. Se ele pesar demais no gate, o gate passa a
  rodar tarefas explícitas.
- **Visualização manual.** Pelo experimento do repo, o que exigir abrir o app
  vira `Débito humano:` no ticket. O dono verifica tudo de uma vez no fim.
- Ordem sugerida: PC primeiro (a engrenagem e os copiáveis criam o arquivo de
  casos), depois a casca do Android, e o resto do Android em paralelo.
