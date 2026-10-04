# CLEAN-033: `down` retenta a parada do emulador mesmo depois de o serial sumir
Status: needs-triage
Origin: revisão Spec do CLEAN-032, 2026-10-04

O CLEAN-032 captura o processo dono do listener antes de `emu kill`, espera
30 s depois de o serial desaparecer e usa `Stop-AndWait` se ele continua
vivo. Quando essa parada falha, o erro inclui o PID e `state.active`
permanece true, conforme o contrato aprovado.

Na chamada seguinte, porém, a captura e a parada só acontecem se o serial
ainda aparece em `adb devices`. Se o serial já sumiu e o processo sobreviveu
à falha, a repetição ignora esse processo e pode gravar `active=false`.
A identidade capturada pertence apenas à primeira chamada.

Avaliar uma retentativa que consiga reencontrar com segurança o processo
do AVD confirmado, sem atingir outro processo ou um PID reutilizado.
Áreas a investigar: derrubada em `qa/down.ps1` e testes em `qa/down.test.mjs`.

## Comments

- 2026-10-04 — Achado por leitura do caminho de erro em `qa/down.ps1`, não
  por uma falha física de parada. O CLEAN-032 autoriza conferir esse caminho
  por leitura e nenhum dos seus cinco critérios exige recuperar a identidade
  entre chamadas. Não é uma reprovação nem uma regressão demonstrada do
  CLEAN-032; este ticket precisa de triagem antes de virar contrato de implementação.
