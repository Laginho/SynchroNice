// A janela do chat. Desenha cada Item recebido como bolha à esquerda ("o
// outro") e cada Item enviado como bolha à direita ("eu"), e enfileira o que
// o composer, o drop e o Ctrl+V mandam para o celular (SYN-004).
import { Channel, invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { getCurrentWebview } from "@tauri-apps/api/webview";
import { openUrl } from "@tauri-apps/plugin-opener";
import { segmentarTexto } from "./copiaveis.ts";

interface TextItem {
  id: number;
  at_ms: number;
  kind: "text";
  text: string;
}

interface FileItem {
  id: number;
  at_ms: number;
  kind: "file";
  name: string;
  size: number;
  received: number;
  status: "receiving" | "done" | "failed";
}

type Item = TextItem | FileItem;

interface EnviadoTexto {
  id: number;
  at_ms: number;
  kind: "text";
  text: string;
  entregue: boolean;
}

interface EnviadoArquivo {
  id: number;
  at_ms: number;
  kind: "file";
  name: string;
  size: number;
  entregue: boolean;
}

type Enviado = EnviadoTexto | EnviadoArquivo;

/** Devolvido por `estado_par_inicial` (SYN-005). */
interface EstadoParInicial {
  mostrar_qr: boolean;
  svg: string;
}

const IMAGEM = new Set(["jpg", "jpeg", "png", "gif", "webp", "bmp"]);
/** Quanto o mouse precisa andar, com o botão apertado, para virar drag e não
 * clique (SYN-006). 4px é o mesmo `SM_CXDRAG` que o Windows usa. */
const LIMIAR_DRAG = 4;
const PREFERENCIA_COPIAR_COM_PONTUACAO = "copy-with-punctuation";
let copiarComPontuacao = localStorage.getItem(PREFERENCIA_COPIAR_COM_PONTUACAO) === "true";

const scroll = document.querySelector<HTMLElement>(".scroll")!;
const empty = scroll.querySelector<HTMLElement>(".empty")!;
/** Bolha recebida já desenhada por id, para atualizar progresso em vez de duplicar. */
const recebidas = new Map<number, HTMLElement>();
/** Bolha enviada já desenhada por id — ids do PC, num espaço à parte dos ids
 * de Item recebido, então as duas listas precisam de mapas separados. */
const enviadas = new Map<number, HTMLElement>();

function hora(atMs: number): string {
  return new Date(atMs).toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit" });
}

function extensao(nome: string): string {
  const i = nome.lastIndexOf(".");
  return i > 0 ? nome.slice(i + 1).toLowerCase() : "";
}

function tamanhoLegivel(bytes: number): string {
  const unidades = ["B", "KB", "MB", "GB"];
  let valor = bytes;
  let i = 0;
  while (valor >= 1024 && i < unidades.length - 1) {
    valor /= 1024;
    i++;
  }
  return `${valor.toFixed(i === 0 ? 0 : 1).replace(".", ",")} ${unidades[i]}`;
}

function renderizarTrechos(texto: string): HTMLElement {
  const body = document.createElement("div");
  body.className = "text";

  for (const trecho of segmentarTexto(texto)) {
    if (trecho.type === "text") {
      body.append(document.createTextNode(trecho.text));
    } else if (trecho.type === "link") {
      const link = document.createElement("a");
      link.href = trecho.href;
      link.textContent = trecho.text;
      body.append(link);
    } else {
      const copiavel = document.createElement("span");
      copiavel.className = "copyable";
      copiavel.textContent = trecho.text;
      copiavel.dataset.copyPlain = trecho.copyPlain;
      copiavel.dataset.copyFormatted = trecho.copyFormatted;
      copiavel.tabIndex = 0;
      copiavel.setAttribute("role", "button");
      copiavel.setAttribute("aria-label", `Copiar ${trecho.kind}: ${trecho.text}`);
      body.append(copiavel);
    }
  }
  return body;
}

function ligarAcoesDeTexto(bolha: HTMLElement, textoCompleto: string): void {
  let inicioPonteiro: { x: number; y: number } | null = null;
  let arrastou = false;

  bolha.addEventListener("pointerdown", (event) => {
    if (event.button !== 0) return;
    inicioPonteiro = { x: event.clientX, y: event.clientY };
    arrastou = false;
  });
  bolha.addEventListener("pointermove", (event) => {
    if (!inicioPonteiro) return;
    arrastou ||= Math.hypot(event.clientX - inicioPonteiro.x, event.clientY - inicioPonteiro.y) > LIMIAR_DRAG;
  });
  bolha.addEventListener("pointercancel", () => {
    inicioPonteiro = null;
    arrastou = false;
  });
  bolha.addEventListener("click", (event) => {
    const alvo = event.target;
    if (!(alvo instanceof Element)) return;
    const link = alvo.closest<HTMLAnchorElement>("a");
    const copiavel = alvo.closest<HTMLElement>(".copyable");
    const selecao = window.getSelection();

    if (arrastou || (selecao && !selecao.isCollapsed)) {
      if (link) event.preventDefault();
      inicioPonteiro = null;
      arrastou = false;
      return;
    }
    inicioPonteiro = null;

    if (link) {
      event.preventDefault();
      void openUrl(link.href);
    } else if (copiavel) {
      event.preventDefault();
      void copiarParaClipboard(textoDoCopiavel(copiavel));
    } else {
      void copiarParaClipboard(textoCompleto);
    }
  });
  bolha.addEventListener("keydown", (event) => {
    if ((event.key !== "Enter" && event.key !== " ") || !(event.target instanceof HTMLElement)) return;
    const copiavel = event.target.closest<HTMLElement>(".copyable");
    if (!copiavel) return;
    event.preventDefault();
    void copiarParaClipboard(textoDoCopiavel(copiavel));
  });
}

function textoDoCopiavel(elemento: HTMLElement): string {
  return copiarComPontuacao ? elemento.dataset.copyFormatted ?? "" : elemento.dataset.copyPlain ?? "";
}

async function copiarParaClipboard(texto: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(texto);
  } catch (e) {
    console.error(`não foi possível copiar o texto: ${e}`);
  }
}

function renderTexto(item: TextItem): HTMLElement {
  const bubble = document.createElement("div");
  bubble.className = "item";

  const time = document.createElement("time");
  time.textContent = hora(item.at_ms);

  bubble.append(renderizarTrechos(item.text), time);
  ligarAcoesDeTexto(bubble, item.text);
  return bubble;
}

/** Troca a miniatura por uma imagem real assim que o arquivo termina de chegar. */
function carregarMiniatura(id: number, nome: string, thumb: HTMLElement): void {
  invoke<number[]>("read_file_bytes", { id })
    .then((bytes) => {
      const blob = new Blob([new Uint8Array(bytes)]);
      const img = document.createElement("img");
      img.src = URL.createObjectURL(blob);
      img.alt = nome;
      thumb.replaceChildren(img);
    })
    .catch((e) => {
      console.error(`não foi possível carregar a miniatura de ${nome}: ${e}`);
    });
}

function atualizarProgresso(bubble: HTMLElement, item: FileItem): void {
  const completo = item.status === "done";
  const falhou = item.status === "failed";
  bubble.classList.toggle("enviando", item.status === "receiving");
  bubble.classList.toggle("falhou", falhou);

  const meta = bubble.querySelector<HTMLElement>(".file-meta")!;
  meta.textContent = completo
    ? `${extensao(item.name).toUpperCase() || "ARQUIVO"} · ${tamanhoLegivel(item.size)}`
    : falhou
      ? "o envio não chegou ao fim"
      : `${tamanhoLegivel(item.received)}${item.size > 0 ? ` de ${tamanhoLegivel(item.size)}` : ""}`;

  if (item.status === "receiving") {
    const barra = bubble.querySelector<HTMLElement>(".progress .bar");
    if (barra && item.size > 0) {
      barra.style.width = `${Math.min(100, (item.received / item.size) * 100)}%`;
    }
    return;
  }

  bubble.querySelector(".progress")?.remove();
  if (completo && IMAGEM.has(extensao(item.name))) {
    const thumb = bubble.querySelector<HTMLElement>(".file-thumb");
    if (thumb) carregarMiniatura(item.id, item.name, thumb);
  }
}

/** A bolha some do jeito que está: o nome permanece, só a legenda muda. A
 * legenda anterior fica guardada, porque o arquivo pode voltar. */
function marcarArquivoSumido(bubble: HTMLElement): void {
  const meta = bubble.querySelector<HTMLElement>(".file-meta");
  if (meta && !bubble.classList.contains("sumiu")) meta.dataset.antes = meta.textContent ?? "";
  bubble.classList.add("sumiu");
  if (meta) meta.textContent = "o arquivo não está mais na Pasta";
}

/** Ligada enquanto um drag saído de uma bolha está no ar (SYN-006). Soltar
 * esse drag em cima da própria janela é um drop como outro qualquer, e sem
 * isto `ligarDrop` mandaria de volta ao celular o arquivo que veio dele. */
let arrastandoDaBolha = false;

/** O arquivo está na Pasta de novo (voltou, ou nunca faltou): a bolha volta
 * como estava, senão ela arrastaria dizendo que o arquivo sumiu. */
function desmarcarArquivoSumido(bubble: HTMLElement): void {
  if (!bubble.classList.contains("sumiu")) return;
  bubble.classList.remove("sumiu");
  const meta = bubble.querySelector<HTMLElement>(".file-meta");
  if (meta?.dataset.antes !== undefined) meta.textContent = meta.dataset.antes;
}

/** Arrasta o arquivo de uma bolha recebida para fora da janela, como o
 * arquivo real que está na Pasta (SYN-006). O backend resolve o caminho e
 * confere que o arquivo ainda existe; só então o plugin de drag assume o
 * rastreio nativo do mouse, no lugar da Drag and Drop API da web (que só
 * entrega o arquivo dentro da própria janela). */
async function iniciarDrag(id: number, nome: string, bubble: HTMLElement): Promise<void> {
  let caminho: string;
  try {
    caminho = await invoke<string>("caminho_para_arrastar", { id });
  } catch (e) {
    // Se o caminho não puder ser resolvido, a bolha deixa de oferecer drag.
    marcarArquivoSumido(bubble);
    console.error(`não foi possível arrastar ${nome}: ${e}`);
    return;
  }
  desmarcarArquivoSumido(bubble);

  // O plugin exige o canal mesmo sem ninguém escutando: o resultado do drop
  // acontece fora da janela e a UI não tem o que fazer com ele.
  const onEvent = new Channel<{ result: "Dropped" | "Cancel"; cursorPos: { x: number; y: number } }>();
  arrastandoDaBolha = true;
  try {
    await invoke("plugin:drag|start_drag", {
      item: [caminho],
      image: caminho,
      options: { mode: "copy" },
      onEvent,
    });
  } catch (e) {
    // Um drag que não começou não diz nada sobre o arquivo: a bolha fica como
    // está, em vez de mentir que ele sumiu.
    console.error(`o drag de ${nome} não começou: ${e}`);
  } finally {
    // O drop acontece dentro do laço nativo, antes de `start_drag` responder;
    // o `setTimeout` só dá folga para o evento do drop, que vem por outro
    // caminho, chegar antes da bandeira baixar.
    setTimeout(() => {
      arrastandoDaBolha = false;
    }, 0);
  }
}

function renderArquivo(item: FileItem): HTMLElement {
  const bubble = document.createElement("div");
  bubble.className = "item file";
  // Só uma bolha terminada arrasta: em progresso, `.enviando` ainda está
  // presente (ver `atualizarProgresso`), e não há arquivo com nome final
  // na Pasta para arrastar ainda; `.falhou` nunca chega a ter um arquivo
  // final também.
  //
  // O drag nativo só começa depois que o mouse anda `LIMIAR_DRAG` com o botão
  // apertado, nunca no `mousedown` cru: uma vez começado, quem solta o botão
  // solta um arquivo, e soltar em cima da própria janela é um drop que o
  // `ligarDrop` mandaria de volta para o celular. Clicar na bolha tem que
  // continuar sendo clicar.
  bubble.addEventListener("mousedown", (event) => {
    if (
      event.button !== 0 ||
      bubble.classList.contains("enviando") ||
      bubble.classList.contains("falhou")
    ) {
      return;
    }
    const x0 = event.clientX;
    const y0 = event.clientY;

    const andou = (movimento: MouseEvent): void => {
      if (
        Math.abs(movimento.clientX - x0) < LIMIAR_DRAG &&
        Math.abs(movimento.clientY - y0) < LIMIAR_DRAG
      ) {
        return;
      }
      soltou();
      void iniciarDrag(item.id, item.name, bubble);
    };
    const soltou = (): void => {
      window.removeEventListener("mousemove", andou);
      window.removeEventListener("mouseup", soltou);
    };

    window.addEventListener("mousemove", andou);
    window.addEventListener("mouseup", soltou);
  });

  const ehImagem = IMAGEM.has(extensao(item.name));
  if (ehImagem) {
    const thumb = document.createElement("div");
    thumb.className = "file-thumb";
    bubble.append(thumb);
  }

  const info = document.createElement("div");
  info.className = "file-info";
  const nome = document.createElement("div");
  nome.className = "file-name";
  nome.textContent = item.name;
  const meta = document.createElement("div");
  meta.className = "file-meta";
  info.append(nome, meta);
  bubble.append(info);

  const progress = document.createElement("div");
  progress.className = "progress";
  const bar = document.createElement("div");
  bar.className = "bar";
  progress.append(bar);
  bubble.append(progress);

  const time = document.createElement("time");
  time.textContent = hora(item.at_ms);
  bubble.append(time);

  atualizarProgresso(bubble, item);
  return bubble;
}

function renderRecebido(item: Item): void {
  const existente = recebidas.get(item.id);
  if (existente) {
    // Só um arquivo em andamento gera evento repetido para o mesmo id.
    if (item.kind === "file") atualizarProgresso(existente, item);
    return;
  }

  empty.hidden = true;
  const bubble = item.kind === "text" ? renderTexto(item) : renderArquivo(item);
  recebidas.set(item.id, bubble);
  scroll.append(bubble);
  bubble.scrollIntoView({ block: "end" });
}

/** Mostra ou some o indicador girando conforme `entregue`, na mesma bolha. */
function atualizarEstadoEnvio(bubble: HTMLElement, rodape: HTMLElement, entregue: boolean): void {
  bubble.classList.toggle("pendente", !entregue);
  const indicador = rodape.querySelector(".pendente-indicador");
  if (entregue) {
    indicador?.remove();
  } else if (!indicador) {
    const novo = document.createElement("span");
    novo.className = "pendente-indicador";
    rodape.prepend(novo);
  }
}

function renderTextoEnviado(item: EnviadoTexto): HTMLElement {
  const bubble = document.createElement("div");
  bubble.className = "item mine";

  const rodape = document.createElement("div");
  rodape.className = "rodape";
  const time = document.createElement("time");
  time.textContent = hora(item.at_ms);
  rodape.append(time);

  bubble.append(renderizarTrechos(item.text), rodape);
  ligarAcoesDeTexto(bubble, item.text);
  atualizarEstadoEnvio(bubble, rodape, item.entregue);
  return bubble;
}

function renderArquivoEnviado(item: EnviadoArquivo): HTMLElement {
  const bubble = document.createElement("div");
  bubble.className = "item file mine";

  const info = document.createElement("div");
  info.className = "file-info";
  const nome = document.createElement("div");
  nome.className = "file-name";
  nome.textContent = item.name;
  const meta = document.createElement("div");
  meta.className = "file-meta";
  meta.textContent = `${extensao(item.name).toUpperCase() || "ARQUIVO"} · ${tamanhoLegivel(item.size)}`;
  info.append(nome, meta);
  bubble.append(info);

  const rodape = document.createElement("div");
  rodape.className = "rodape";
  const time = document.createElement("time");
  time.textContent = hora(item.at_ms);
  rodape.append(time);
  bubble.append(rodape);

  atualizarEstadoEnvio(bubble, rodape, item.entregue);
  return bubble;
}

function renderEnviado(item: Enviado): void {
  const existente = enviadas.get(item.id);
  if (existente) {
    const rodape = existente.querySelector<HTMLElement>(".rodape")!;
    atualizarEstadoEnvio(existente, rodape, item.entregue);
    return;
  }

  // Confirmação de um Item que esta Sessão nunca desenhou: ele foi enfileirado
  // antes de sair pela bandeja e o celular só o buscou agora. A fila sobrevive
  // ao reinício, a Sessão não — desenhar a bolha aqui encheria um chat que
  // precisa abrir vazio.
  if (item.entregue) return;

  empty.hidden = true;
  const bubble = item.kind === "text" ? renderTextoEnviado(item) : renderArquivoEnviado(item);
  enviadas.set(item.id, bubble);
  scroll.append(bubble);
  bubble.scrollIntoView({ block: "end" });
}

function atualizarPresenca(online: boolean): void {
  const indicador = document.querySelector<HTMLElement>("#presenca")!;
  indicador.dataset.online = online ? "true" : "false";
  indicador.title = online ? "o celular está na rede" : "o celular está fora da rede";
}

/** Mostra a tela de QR com o `svg` do Par atual, no lugar do chat (SYN-005).
 * O SVG vem do próprio PC (nunca da rede), então injetar como HTML é seguro. */
function mostrarQr(svg: string): void {
  document.querySelector<HTMLElement>("#qr-screen .qr-svg")!.innerHTML = svg;
  document.querySelector<HTMLElement>("#qr-screen")!.hidden = false;
  document.querySelector<HTMLElement>("#frame")!.hidden = true;
}

function mostrarChat(): void {
  document.querySelector<HTMLElement>("#qr-screen")!.hidden = true;
  document.querySelector<HTMLElement>("#frame")!.hidden = false;
}

/** Abre as configurações da Pasta e do Par no cabeçalho da janela. */
function ligarConfiguracoes(): void {
  const toggle = document.querySelector<HTMLButtonElement>("#settings-toggle")!;
  const panel = document.querySelector<HTMLElement>("#settings-panel")!;
  const mudarPasta = document.querySelector<HTMLButtonElement>("#change-folder")!;
  const desparear = document.querySelector<HTMLButtonElement>("#unpair")!;
  const copiarComPontuacaoInput = document.querySelector<HTMLInputElement>("#copy-with-punctuation")!;
  copiarComPontuacaoInput.checked = copiarComPontuacao;

  copiarComPontuacaoInput.addEventListener("change", () => {
    copiarComPontuacao = copiarComPontuacaoInput.checked;
    localStorage.setItem(PREFERENCIA_COPIAR_COM_PONTUACAO, String(copiarComPontuacao));
  });

  function fechar(): void {
    panel.hidden = true;
    toggle.setAttribute("aria-expanded", "false");
  }

  toggle.addEventListener("click", () => {
    const abrir = panel.hidden;
    panel.hidden = !abrir;
    toggle.setAttribute("aria-expanded", String(abrir));
  });

  document.addEventListener("click", (event) => {
    const alvo = event.target;
    if (
      !panel.hidden &&
      alvo instanceof Node &&
      !panel.contains(alvo) &&
      !toggle.contains(alvo)
    ) {
      fechar();
    }
  });

  window.addEventListener("keydown", (event) => {
    if (event.key !== "Escape" || panel.hidden) return;
    fechar();
    toggle.focus();
  });

  mudarPasta.addEventListener("click", async () => {
    mudarPasta.disabled = true;
    try {
      if (await invoke<boolean>("mudar_pasta")) fechar();
    } catch (e) {
      console.error(`não foi possível mudar a Pasta: ${e}`);
    } finally {
      mudarPasta.disabled = false;
    }
  });

  desparear.addEventListener("click", async () => {
    desparear.disabled = true;
    try {
      const svg = await invoke<string>("parear_de_novo");
      fechar();
      mostrarQr(svg);
    } catch (e) {
      console.error(`não foi possível desparear: ${e}`);
    } finally {
      desparear.disabled = false;
    }
  });
}

/** Enter no composer, ou o botão de enviar: enfileira o texto e limpa o campo. */
function ligarComposer(): void {
  const input = document.querySelector<HTMLInputElement>("#composer-input")!;
  const botao = document.querySelector<HTMLButtonElement>(".composer .send")!;

  async function enviar(): Promise<void> {
    const texto = input.value.trim();
    if (!texto) return;
    input.value = "";
    try {
      await invoke("send_text", { text: texto });
    } catch (e) {
      console.error(`não foi possível enfileirar o texto: ${e}`);
    }
  }

  input.addEventListener("keydown", (event) => {
    if (event.key === "Enter") void enviar();
  });
  botao.addEventListener("click", () => void enviar());
}

/** Arrastar um arquivo do Explorer para a janela: um Item por caminho,
 * nunca agrupados (spec, "Enviar do PC"). Tauri intercepta o drop nativo da
 * janela e entrega caminhos, não `File`s do DOM. */
function ligarDrop(): void {
  void getCurrentWebview().onDragDropEvent((event) => {
    if (event.payload.type !== "drop") return;
    // Arquivo que saiu de uma bolha e foi solto de volta na janela: quem
    // desistiu do drag não pediu para reenviar ao celular (SYN-006).
    if (arrastandoDaBolha) return;
    invoke("send_files", { paths: event.payload.paths }).catch((e) => {
      console.error(`não foi possível enfileirar os arquivos soltos: ${e}`);
    });
  });
}

/** A primeira imagem da colagem, extraída de forma síncrona: `clipboardData`
 * só é legível de dentro do handler, qualquer `await` antes disso o esvazia.
 * O `File` devolvido é uma cópia dos bytes, então pode ser lido depois.
 *
 * Vem do evento, não de `navigator.clipboard.read()`: essa API depende, no
 * WebView2, de um grant de `CLIPBOARD_READ` que o wry só instala quando o app
 * chama `enable_clipboard_access()` — este não chama, e sem o grant a
 * permissão cai no prompt do próprio WebView2. Colar um print não pode abrir
 * uma caixa de diálogo. `clipboardData` não pede permissão nenhuma. */
function imagemDaColagem(event: ClipboardEvent): { arquivo: File; ext: string } | null {
  const itens = event.clipboardData?.items;
  if (!itens) return null;
  for (let i = 0; i < itens.length; i++) {
    const item = itens[i];
    if (item.kind !== "file" || !item.type.startsWith("image/")) continue;
    const arquivo = item.getAsFile();
    if (arquivo) return { arquivo, ext: item.type.split("/")[1] ?? "png" };
  }
  return null;
}

/** Ctrl+V: arquivo(s) copiados no Explorer vêm em `CF_HDROP`, formato que só
 * o lado Rust lê e que é o sinal inequívoco de "copiei arquivos" — por isso
 * vem primeiro, senão a miniatura que o Explorer põe junto de um .png faria
 * um arquivo copiado virar bolha de imagem sem nome. Sem `CF_HDROP`, o que
 * sobra é um print, e ele sai do próprio evento de colagem. */
function ligarColar(): void {
  window.addEventListener("paste", (event) => {
    void tratarColagem(event);
  });
}

async function tratarColagem(event: ClipboardEvent): Promise<void> {
  const imagem = imagemDaColagem(event);
  // Colagem de texto puro não tem nem imagem nem arquivo: sai antes de
  // incomodar o lado Rust, e o texto cai no composer como sempre.
  const temArquivos = event.clipboardData?.types.includes("Files") ?? false;
  if (!imagem && !temArquivos) return;

  try {
    const caminhos = await invoke<string[]>("read_clipboard_file_list");
    if (caminhos.length > 0) {
      await invoke("send_files", { paths: caminhos });
      return;
    }
  } catch (e) {
    console.error(`clipboard sem lista de arquivos, tentando a imagem: ${e}`);
  }

  if (!imagem) return;
  try {
    const bytes = Array.from(new Uint8Array(await imagem.arquivo.arrayBuffer()));
    await invoke("send_bytes", { name: `imagem-${Date.now()}.${imagem.ext}`, bytes });
  } catch (e) {
    console.error(`não foi possível enfileirar a imagem colada: ${e}`);
  }
}

async function main(): Promise<void> {
  // Escuta antes de listar, para não perder um Item que chegue no meio;
  // `recebidas`/`enviadas` descartam o que vier duas vezes como criação.
  await listen<Item>("item", (event) => renderRecebido(event.payload));
  await listen<Enviado>("enviado", (event) => renderEnviado(event.payload));
  await listen<boolean>("presenca", (event) => {
    atualizarPresenca(event.payload);
    // O celular só conecta com o token certo: presença é o próprio sinal de
    // que o pareamento (o QR desta tela ou de um anterior) já vale.
    if (event.payload) mostrarChat();
  });

  // Se o servidor não subiu, o Par não existe no backend e este comando
  // falha. A janela não pode ficar em branco por causa disso (as duas telas
  // nascem `hidden`): cai no chat, que é o estado utilizável.
  const estadoPar = await invoke<EstadoParInicial>("estado_par_inicial").catch(
    (e): EstadoParInicial => {
      console.error(`não foi possível ler o estado do Par: ${e}`);
      return { mostrar_qr: false, svg: "" };
    },
  );
  if (estadoPar.mostrar_qr) mostrarQr(estadoPar.svg);
  else mostrarChat();

  // Só os recebidos da Sessão: a lista de Itens é em memória e morre com o
  // processo. A fila de saída é persistida e sobrevive, mas não é Sessão —
  // sair pela bandeja e reabrir tem que mostrar um chat vazio, com os
  // Pendentes ainda na fila, esperando o celular.
  for (const item of await invoke<Item[]>("list_items")) renderRecebido(item);

  ligarComposer();
  ligarDrop();
  ligarColar();
  ligarConfiguracoes();
}

void main();
