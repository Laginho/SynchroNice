# CLEAN-001: Teste de concorrência instável derruba o gate
Stage: to-implement
Status: ready-for-agent
Blocked by: none
Review: agent

- Primary files:
  - pc/src-tauri/tests/server.rs (só o teste instável e o que ele precisa para ser determinístico)

#### What to build

O gate falha de vez em quando sem ninguém ter mexido em `pc/`. Quem roda o
gate precisa poder ler "vermelho" como "eu quebrei alguma coisa", e hoje não
pode: uma segunda rodada, sem nenhuma mudança, passa.

Observado duas vezes em 2026-09-20, as duas no binário de teste `--test
server` do `pc/src-tauri`, durante o trabalho de SYN-007 (que não toca em
`pc/`):

- stage 2 de SYN-007: `dois_envios_concorrentes_do_mesmo_nome_preservam_ambos`
  falhando com `NotFound`; rodada seguinte, verde.
- revisão de SYN-007: uma rodada de gate em quatro com `test result: FAILED.
  17 passed; 1 failed` no mesmo binário (o nome do teste não foi capturado).

A suspeita é o teste de nomes concorrentes: ele depende de tempo entre
reservar o nome e o arquivo final aparecer na Pasta. Confirmar qual é o teste
(rodar o binário em laço até reproduzir) e torná-lo determinístico — esperar
o evento em vez do relógio, ou fazer a costura devolver quando a reserva
virou arquivo. Não é para marcar o teste como `ignored`: o comportamento que
ele cobre é de SYN-003 e continua valendo.

#### Acceptance criteria

1. O teste instável está identificado por nome, e a causa da instabilidade
   descrita em uma linha no corpo deste ticket.
2. Rodar o binário de teste do `server` 20 vezes seguidas passa 20 vezes.
3. O teste continua cobrindo o mesmo comportamento de SYN-003 (dois envios
   concorrentes do mesmo nome preservam os dois arquivos) — nada de
   `#[ignore]` nem de afrouxar a asserção.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Mais a repetição do critério 2, com a saída colada no ticket.

## Tests stage 2 writes (own commit, red)

- Nenhum teste novo: o teste existe e é ele o objeto do ticket. A prova aqui
  é a repetição — vermelho intermitente em N rodadas antes, 20/20 verde
  depois, as duas saídas no ticket.

## Comments

- 2026-09-20 (revisão de SYN-007): aberto pela revisão, fora dos Primary
  files de SYN-007 (nada de `pc/` mudou lá). Teste instável no gate é risco
  de toda sessão futura do fluxo — é o portão que decide `to-review`.
- 2026-09-20 (revisão de SYN-008): terceira observação, mesma proporção de
  antes — uma rodada de gate em quatro falhou na branch de sessão logo após o
  merge de SYN-008, e as três seguintes passaram sem nenhuma mudança. O nome
  do teste de novo não foi capturado (só o fim da saída foi olhado), mas a
  rodada que falhou traz `gradle check` BUILD SUCCESSFUL antes do
  `gate: FALHOU`, então a falha está antes do Android: `cargo fmt`,
  `cargo clippy`, `cargo test` ou `tsc`. SYN-008 não toca em `pc/` nem em
  `pc/ui/`. Recado para quem pegar este ticket: as três observações perderam o
  nome do teste por olhar só o `tail` da saída — rodar o gate com a saída
  inteira guardada em arquivo, ou o binário `--test server` em laço, é o
  primeiro passo do critério 1.
