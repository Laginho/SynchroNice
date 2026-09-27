# Run log

Cópia de `.scratch/run-log.md`, commitada pelo foreman ao fim de cada run do sweatshop.

O original fica fora do git de propósito: o driver o põe em `.git/info/exclude` porque roda `reset --hard` e `checkout -f` entre os estágios, e um arquivo rastreado seria sobrescrito no meio do run. Esta cópia é o histórico que sobrevive à máquina, e é a base para comparar modelos por estágio (`sweatshop/scripts/scoreboard.ps1`, junto com `.scratch/reopen-attribution.md`).
