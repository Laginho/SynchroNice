# SYN-012: Casca do Android
Stage: to-implement
Status: ready-for-agent
Blocked by: none
Review: agent

- Primary files:
  - New: android/app/** (módulo do app: Compose, Activity única, leitor de QR, chat)
  - android/settings.gradle.kts
  - android/build.gradle.kts, android/gradle.properties (novos se precisar)
  - android/gradle/wrapper/gradle-wrapper.properties (se o plugin Android exigir Gradle mais novo)
  - android/protocol-client/src/main/kotlin/** (só leitura do conteúdo do QR para o Par, se ainda não existir)
  - android/protocol-client/src/test/kotlin/**
  - gate.ps1 (só se o `check` do módulo Android precisar de tarefas explícitas)
  - .gitignore

#### What to build

Spec: `.scratch/android/spec.md`, histórias 1 a 4 e 8 a 13, e a seção "App
Android". Retoma os critérios adiados de SYN-007 (`.scratch/core/issues/07-*`).

O app Android existe e roda no celular, com mínimo Android 10. Um módulo do app
entra ao lado do módulo cliente do protocolo, que continua JVM puro e é
consumido pelo app.

Sem Par, a Activity abre direto na câmera (CameraX com decodificação ZXing, sem
Play Services). O QR do PC é um JSON com nome de instância, porta e token.
Ler o QR grava o Par, e o chat abre. Com Par, o app abre direto no chat.

O celular encontra o PC resolvendo o nome de instância por mDNS (`NsdManager`,
implementando o resolvedor do módulo cliente). Se falhar, tenta o último IP
conhecido, guardado nas preferências. O HTTP sem cifra é liberado na
configuração de segurança de rede (ADR-0002).

A conexão de escuta abre quando o chat aparece e fecha quando sai. Ela é o
indicador de presença no topo. Textos do PC viram bolhas à esquerda e são
confirmados ao aparecer. O composer tem campo e botão de enviar; o texto vira
bolha à direita, Pendente até o PC confirmar e depois Entregue. O chat vazio
diz o que fazer. O chat é memória do processo: some quando o Android mata o app.

Build: o SDK da máquina só tem a plataforma `android-37`. Subir o Gradle e
escolher o plugin Android que aceite essa plataforma faz parte deste ticket. O
gate continua passando, com os testes JVM do módulo cliente inclusos.

#### Acceptance criteria

1. O conteúdo do QR do PC (JSON com nome de instância, porta e token) vira o Par; um conteúdo inválido é recusado sem gravar nada.
2. `gate.ps1` compila o módulo do app e continua rodando os testes JVM do módulo cliente.
3. Primeiro uso: o app abre na câmera; ler o QR do PC grava o Par e abre o chat; reabrir o app vai direto para o chat.
4. Na mesma Wi-Fi, o app encontra o PC sem digitar nada, e o indicador mostra "na rede".
5. Trocar o IP do PC (reconectar o Wi-Fi) e o celular volta a encontrá-lo sem reescanear.
6. Um texto enviado do PC aparece como bolha à esquerda no celular, com o app aberto.
7. Um texto digitado no celular aparece no PC, e a bolha à direita passa de Pendente a Entregue.
8. Com o PC fora da rede, o texto digitado fica Pendente e é entregue quando o PC volta, com o app aberto.
9. O chat vazio mostra uma instrução de uso.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 3 a 9 exigem o celular e o PC: verificação manual. O que não der
para ver vira `Débito humano:` (regra "Humano simulado" do `AGENTS.md`).

## Tests stage 2 writes (own commit, red)

- `android/protocol-client/src/test/kotlin/`: ler o conteúdo do QR para o Par,
  com um caso válido e casos inválidos (JSON quebrado, campo faltando, porta
  fora da faixa). Red porque essa leitura ainda não existe. Se ela já existir
  e cobrir isso, o ticket não escreve teste nesta costura e diz isso no commit.

## Comments

- 2026-09-24 Attempt 1 failed: exit 0. Log tail: Encontrei SYN-012 em `Stage: to-implement`, com árvore de trabalho limpa. O binding do repositório atribui o stage 2 ao **gpt-6-luna max**, mas esta sessão não está identificada como esse modelo. Pelo `ticket-flow`, devo parar sem implementar; o próximo passo é executar SYN-012 com gpt-6-luna max.

- 2026-09-24 Attempt 2 failed: exit 0; blocked after two attempts. Log tail: Parei conforme a regra de modelo da `ticket-flow`: [SYN-012](D:/Desktop/Projects/SynchroNice/.scratch/android/issues/03-SYN-012-casca-do-android.md) está em `to-implement`, e o `AGENTS.md` atribui o stage 2 ao **gpt-6-luna max**. Esta sessão não está identificada como esse modelo. /  / Não alterei arquivos nem rodei testes. O ticket já registra uma tentativa anterior com o mesmo impedimento; o próximo passo é executar SYN-012 com gpt-6-luna max.

- 2026-09-24 De volta a to-implement: o bloqueio era o guard de modelo do `ticket-flow` (Codex não informa o modelo à sessão), corrigido no skill. O ticket em si não tem pendência.
