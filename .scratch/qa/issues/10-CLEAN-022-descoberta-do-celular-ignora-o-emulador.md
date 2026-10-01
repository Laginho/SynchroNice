# CLEAN-022: Descoberta automática do celular ignora o emulador
Status: needs-triage
Blocked by: none
Origin: revisão do PR 8 (sessão sweatshop/2026-09-30-2146), 2026-10-01

## Comments

- `Wireless` em `qa/phone.ps1:26` aceita qualquer `host:porta`, e com isso
  também o `127.0.0.1:5555` que o `qa/up.ps1:87` conecta. Se o `down` falhou
  ou não rodou, a descoberta em `:121-123` escolhe o emulador, e o pós-run
  "do Redmi" do foreman roda nele em silêncio. O `pair` usaria então o config
  real, com o token real. Isso contraria o `docs/agents/qa.md` ("não escolhe
  emuladores") e o critério 3 de SYN-022.
- Proposta para triagem: descartar endpoints de loopback, ou recusar aparelho
  com `ro.kernel.qemu=1`, com um caso no adb falso.
