# Spec: SynchroNice

Status: ready-for-agent

Fonte: sessão de grilling de 2026-09-16 (4 rodadas) e protótipo `prototype/chat-prototype.html`.
Vocabulário: `CONTEXT.md`. Decisões: `docs/adr/0001`, `docs/adr/0002`, `docs/DECISIONS.md`.

## Problem Statement

Passar texto, imagens e arquivos entre o celular Android e o PC Windows na
rede de casa exige hoje abrir um link nos dois lados, sincronizar, escolher
arquivo num popup. Parece preencher um formulário. Quando a sessão do
WhatsApp Web expira, o autor simplesmente desiste e faz pelo celular. As
alternativas (PairDrop, LocalSend, KDE Connect) ou têm setup repetido ou
fazem mais do que o necessário.

## Solution

Um chat de grupo solo entre o celular e o PC. Pareia uma vez por QR e nunca
mais. No celular, "Compartilhar" para o SynchroNice manda direto para o PC
sem abrir nada. No PC, um app leve na bandeja que abre com o Windows: texto
com Enter, arquivo arrastado ou colado. Tudo aparece como bolha de chat nos
dois lados, "eu" à direita e "o outro" à esquerda. Arquivos recebidos no PC
vão para uma Pasta escolhida uma vez e podem ser arrastados da bolha direto
para outro programa. Sem nuvem, sem servidor, sem histórico: a Sessão é
descartada ao encerrar, o Par persiste.

## User Stories

### Pareamento e conexão
1. Como usuário, quero ver um QR no PC no primeiro uso, para parear escaneando com o celular.
2. Como usuário, quero escanear o QR uma única vez, para nunca mais precisar parear.
3. Como usuário, quero que o celular encontre o PC sozinho quando os dois estiverem na mesma Wi-Fi, para não digitar IP nem reescanear.
4. Como usuário, quero que parear de novo substitua o Par anterior, para trocar de PC sem menu de "qual aparelho".
5. Como usuário, quero que só o aparelho pareado consiga mandar coisas para o meu PC, para outro aparelho na Wi-Fi não despejar coisas no meu chat.
6. Como usuário, quero ver no chat se o outro lado está na rede ou não, para saber por que algo está pendente.

### Enviar do celular
7. Como usuário, quero compartilhar uma foto da galeria para o SynchroNice pelo "Compartilhar" do Android, para ela aparecer no PC sem abrir o app.
8. Como usuário, quero compartilhar texto ou link de qualquer app para o SynchroNice, para ele aparecer no chat do PC.
9. Como usuário, quero compartilhar qualquer arquivo (PDF, vídeo, áudio), para ele chegar na Pasta do PC.
10. Como usuário, quero compartilhar várias fotos de uma vez e vê-las como Itens separados, nunca agrupados, para arrastar cada uma individualmente.
11. Como usuário, quero abrir o app no celular, digitar texto e apertar enviar, para mandar uma anotação rápida.
12. Como usuário, quero que um envio com o PC fora da rede fique Pendente no celular e seja entregue sozinho quando o PC voltar, para não ter que lembrar de reenviar.

### Enviar do PC
13. Como usuário, quero digitar no PC e apertar Enter, para mandar texto ao celular.
14. Como usuário, quero arrastar um arquivo do Explorer para a janela do chat, para mandá-lo ao celular.
15. Como usuário, quero dar Ctrl+V com um print no clipboard, para mandar a imagem ao celular.
16. Como usuário, quero dar Ctrl+V com um arquivo copiado no Explorer, para mandá-lo ao celular.
17. Como usuário, quero que o PC não tenha botão de anexar, para o chat não virar formulário.
18. Como usuário, quero que um envio do PC com o celular fora da rede fique Pendente e seja entregue quando ele aparecer.

### Receber e usar
19. Como usuário, quero ver cada Item como bolha no chat de ambos os aparelhos, com "eu" à direita e "o outro" à esquerda.
20. Como usuário, quero que imagens apareçam como miniatura na bolha, para saber o que é sem abrir.
21. Como usuário, quero que outros arquivos mostrem nome, tipo e tamanho na bolha.
22. Como usuário, quero arrastar uma imagem ou arquivo da bolha no PC direto para outro programa, para não procurar na Pasta.
23. Como usuário, quero que arquivos recebidos no PC sejam gravados na Pasta escolhida no primeiro uso, como arquivos comuns.
24. Como usuário, quero que um nome repetido na Pasta vire `nome (1).ext`, para nunca sobrescrever.
25. Como usuário, quero que uma imagem recebida no celular apareça como miniatura no chat sem ir para a galeria, e que um toque a salve.
26. Como usuário, quero ver progresso animado na bolha de um arquivo grande, para saber que não travou.
27. Como usuário, quero clicar em um link recebido e abrir no navegador.

### Sessão e ciclo de vida
28. Como usuário, quero que fechar a janela do PC só a esconda na bandeja, para não perder a Sessão sem querer.
29. Como usuário, quero que o app do PC abra com o Windows, para o PC estar sempre disponível quando o celular manda algo.
30. Como usuário, quero que sair pela bandeja ou reiniciar o Windows descarte a Sessão, e que arquivos na Pasta permaneçam.
31. Como usuário, quero que o app do PC seja leve em disco e RAM, para deixá-lo sempre aberto sem pesar.
32. Como usuário, quero que o chat vazio diga o que fazer, para não precisar de tutorial.

## Implementation Decisions

- **Dois apps, um protocolo.** PC em Tauri (Rust + WebView2, interface HTML/TS sem framework). Android em Kotlin nativo. ADR-0001.
- **O PC é o servidor, o celular é o cliente.** O PC expõe um serviço HTTP na rede local numa porta fixa. O celular sempre inicia a conexão. Simplifica NAT, permissões e ciclo de vida no Android.
- **Descoberta.** O PC anuncia o serviço por mDNS na rede local com um nome de instância derivado do Par. O celular resolve esse nome para achar o IP atual. Se o mDNS falhar, o celular tenta o último IP conhecido.
- **Pareamento.** O QR carrega o nome de instância e um token secreto. O celular guarda ambos; o PC guarda o token. Toda requisição leva o token; sem ele, recusa. Sem cifra (decisão explícita do autor, ADR-0002).
- **Um Par.** Parear de novo gera token novo e invalida o anterior. A fila de Pendentes é descartada junto: nada destinado ao Par antigo vai para o novo.
- **Protocolo de Itens.** Enviar texto: uma requisição com o corpo. Enviar arquivo: uma requisição com nome, tamanho e bytes em stream, para permitir progresso. Direção PC para celular: o celular mantém uma conexão de escuta enquanto o app está aberto e baixa cada Item anunciado. Presença: a conexão de escuta ativa é o que define "o outro está na rede". Cada Item leva um id gerado pelo remetente; o destinatário responde confirmando o id e ignora id já recebido. Só a confirmação remove o Item da fila de Pendentes, então reenvio nunca duplica bolha nem arquivo.
- **Fila de Pendentes.** Cada lado guarda em disco os Itens que não conseguiu entregar (texto em linha, arquivo copiado para uma pasta interna do app, nunca só o caminho, porque PNG temporário, arquivo movido e URI de Compartilhar do Android não sobrevivem até a entrega). Ao detectar o outro lado, drena em ordem. É o único estado persistido além do Par e da Pasta.
- **Sessão em memória.** O chat visível é uma lista em memória. Encerrar de fato o app limpa. Fechar a janela do PC esconde na bandeja.
- **Pasta.** Escolhida via diálogo nativo no primeiro uso, gravada na configuração. Nomes repetidos recebem sufixo ` (1)`, ` (2)`, como o Windows.
- **Entrada no PC.** Enter envia texto. Drop nativo da janela recebe caminhos. Ctrl+V: se o clipboard tem imagem, grava um PNG temporário e envia; se tem lista de arquivos (formato de lista do Windows, lido no lado Rust), envia cada um. Sem botão de anexar.
- **Arrastar para fora no PC.** A bolha de arquivo inicia um drag nativo de arquivo (API do Tauri), com o caminho na Pasta. Funciona para qualquer tipo, não só imagem.
- **Android.** Activity única com o chat. Intent filter de SEND e SEND_MULTIPLE para texto e qualquer mime. Múltiplos itens viram Itens separados. Envio roda em um serviço em primeiro plano com notificação de progresso, para sobreviver ao app sair de tela. Miniatura de imagem recebida fica no cache do app; salvar na galeria é um toque.
- **UI.** Variante A do protótipo: bolhas, "eu" à direita. Estilo Slip: fundo escuro, bolhas claras. Progresso é uma barra animada dentro da bolha. Pendente reduz a opacidade e mostra um indicador girando.
- **Bandeja e autostart no PC.** Ícone na bandeja com "Abrir" e "Sair". Registro em autostart do Windows no primeiro uso.

## Testing Decisions

- **Um teste bom exercita comportamento externo**: o que chega na rede, o que fica em disco, o que a lista de Itens contém. Nunca funções internas.
- **Costura do PC: o protocolo HTTP.** Testes de integração em Rust sobem o servidor numa porta aleatória, agem como o celular (mandam texto, mandam arquivo em stream, escutam Itens) e verificam a Pasta e a lista de Itens. Cobre pareamento, token, fila de Pendentes, sufixo de nome, progresso.
- **Costura do Android: o cliente do protocolo.** Testes JVM (sem emulador) do módulo de cliente contra um servidor falso local: envio de texto, arquivo em stream com progresso, fila de Pendentes, reconexão.
- **Fora de teste automático**: interface, drag nativo, bandeja, autostart, mDNS real, Compartilhar do Android. Verificação manual por critério, descrita em cada ticket.
- **Sem prior art**: repositório novo. Os testes do primeiro ticket de cada app definem o padrão.

## Out of Scope

- Cifra do tráfego. Rede doméstica, uso pessoal.
- Fora da mesma Wi-Fi (relay, túnel, nuvem).
- Histórico persistente do chat.
- Mais de um Par (dois PCs ou dois celulares).
- iOS, Linux, macOS.
- Agrupar itens, álbuns, pastas compactadas.
- Editar ou responder mensagens, e apagar um Item só. Apagar a conversa
  inteira nos dois aparelhos existe desde 2026-10-01 (SYN-019, SYN-027).
- Publicação em loja ou instalador assinado.

## Further Notes

- Protótipo descartável em `prototype/chat-prototype.html`. Quando o app real existir, mover para um branch descartável.
- Ordem de construção: PC primeiro, testável com `curl` antes de existir Kotlin.
- Este projeto é pessoal. Cada corte que reduz cognição do autor no momento de uso vale mais que completude.
