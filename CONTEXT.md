# SynchroNice — Glossário

Chat de grupo solo entre um celular Android e um PC Windows, na mesma rede
Wi-Fi, sem nuvem. Vocabulário canônico do domínio; sem detalhes de implementação.

## Termos

**Par** — O vínculo entre o PC e o celular. Criado uma vez, escaneando um QR
mostrado pelo PC. Persiste indefinidamente. Existe no máximo um Par; parear de
novo substitui o anterior.

**Sessão** — O conteúdo visível no chat de um aparelho. Dura enquanto o app
está vivo. Esconder a janela do PC não encerra a Sessão; sair pela bandeja ou
reiniciar o Windows encerra. No celular, encerra quando o Android mata o app.
Ao encerrar, o texto da Sessão é descartado.

**Item** — Uma unidade enviada de um aparelho ao outro: um texto ou um arquivo
(imagem inclusa). Aparece no chat de ambos os lados.

**Pendente** — Estado de um Item cujo aparelho de destino não está na rede. O
Item fica no aparelho de origem e é entregue sozinho quando o destino aparecer.

**Entregue** — Estado de um Item que chegou ao aparelho de destino.

**Pasta** — Diretório no PC onde arquivos recebidos são gravados. Escolhida
uma vez, no primeiro uso. Os arquivos são arquivos comuns do sistema: o app
não gerencia sua vida depois de gravados, e sobrevivem ao fim da Sessão.

**Compartilhar** — O caminho principal de entrada no celular: a ação
"Compartilhar" do Android com o SynchroNice como destino, sem abrir o chat.
