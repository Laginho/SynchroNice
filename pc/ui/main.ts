// A janela do chat. Desenha cada Item recebido como bolha à esquerda ("o
// outro") e cada Item enviado como bolha à direita ("eu"), e enfileira o que
// o composer, o drop e o Ctrl+V mandam para o celular (SYN-004).
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { getCurrentWebview } from "@tauri-apps/api/webview";
import { openUrl } from "@tauri-apps/plugin-opener";

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
  done: boolean;
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

const IMAGEM = new Set(["jpg", "jpeg", "png", "gif", "webp", "bmp"]);

const scroll = document.querySelector<HTMLElement>(".scroll")!;
const empty = scroll.querySelector<HTMLElement>(".empty")!;
/** Bolha recebida já desenhada por id, para atualizar progresso em vez de duplicar. */
const recebidas = new Map<number, HTMLElement>();
/** Bolha enviada já desenhada por id — ids do PC, num espaço à parte dos ids
 * de Item recebido, então as duas listas precisam de mapas separados. */
const enviadas = new Map<number, HTMLElement>();

/** Só um texto que é, inteiro, uma URL http(s) vira link. */
function asUrl(text: string): string | null {
  const candidate = text.trim();
  if (/\s/.test(candidate)) return null;
  try {
    const url = new URL(candidate);
    return url.protocol === "http:" || url.protocol === "https:" ? candidate : null;
  } catch {
    return null;
  }
}

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

function linkDeTexto(texto: string): HTMLElement {
  const body = document.createElement("div");
  body.className = "text";
  // textContent, nunca innerHTML: HTML recebido aparece como texto literal.
  const url = asUrl(texto);
  if (url) {
    const link = document.createElement("a");
    link.href = url;
    link.textContent = texto;
    link.addEventListener("click", (event) => {
      event.preventDefault();
      void openUrl(url);
    });
    body.append(link);
  } else {
    body.textContent = texto;
  }
  return body;
}

function renderTexto(item: TextItem): HTMLElement {
  const bubble = document.createElement("div");
  bubble.className = "item";

  const time = document.createElement("time");
  time.textContent = hora(item.at_ms);

  bubble.append(linkDeTexto(item.text), time);
  return bubble;
}

/** Troca a miniatura por uma imagem real assim que o arquivo termina de chegar. */
function carregarMiniatura(nome: string, thumb: HTMLElement): void {
  invoke<number[]>("read_file_bytes", { name: nome })
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
  const completo = item.done;
  bubble.classList.toggle("enviando", !completo);

  const meta = bubble.querySelector<HTMLElement>(".file-meta")!;
  meta.textContent = completo
    ? `${extensao(item.name).toUpperCase() || "ARQUIVO"} · ${tamanhoLegivel(item.size)}`
    : `${tamanhoLegivel(item.received)}${item.size > 0 ? ` de ${tamanhoLegivel(item.size)}` : ""}`;

  const barra = bubble.querySelector<HTMLElement>(".progress .bar");
  if (barra && item.size > 0) {
    barra.style.width = `${Math.min(100, (item.received / item.size) * 100)}%`;
  }

  if (completo) {
    bubble.querySelector(".progress")?.remove();
    if (IMAGEM.has(extensao(item.name))) {
      const thumb = bubble.querySelector<HTMLElement>(".file-thumb");
      if (thumb) carregarMiniatura(item.name, thumb);
    }
  }
}

function renderArquivo(item: FileItem): HTMLElement {
  const bubble = document.createElement("div");
  bubble.className = "item file";

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

  bubble.append(linkDeTexto(item.text), rodape);
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
    invoke("send_files", { paths: event.payload.paths }).catch((e) => {
      console.error(`não foi possível enfileirar os arquivos soltos: ${e}`);
    });
  });
}

/** Ctrl+V: imagem no clipboard (print) vira PNG lido aqui mesmo pela
 * Clipboard API do WebView2; arquivo(s) copiados no Explorer usam o formato
 * `CF_HDROP`, que essa API não expõe — só o lado Rust lê. */
function ligarColar(): void {
  window.addEventListener("paste", () => {
    void tratarColagem();
  });
}

async function tratarColagem(): Promise<void> {
  try {
    const itensDoClipboard = await navigator.clipboard.read();
    for (const item of itensDoClipboard) {
      const tipoDeImagem = item.types.find((t) => t.startsWith("image/"));
      if (!tipoDeImagem) continue;
      const blob = await item.getType(tipoDeImagem);
      const bytes = Array.from(new Uint8Array(await blob.arrayBuffer()));
      const ext = tipoDeImagem.split("/")[1] ?? "png";
      await invoke("send_bytes", { name: `imagem-${Date.now()}.${ext}`, bytes });
      return;
    }
  } catch (e) {
    console.error(`clipboard sem imagem legível, tentando lista de arquivos: ${e}`);
  }

  try {
    const caminhos = await invoke<string[]>("read_clipboard_file_list");
    if (caminhos.length > 0) {
      await invoke("send_files", { paths: caminhos });
    }
  } catch (e) {
    console.error(`não foi possível colar do clipboard: ${e}`);
  }
}

async function main(): Promise<void> {
  // Escuta antes de listar, para não perder um Item que chegue no meio;
  // `recebidas`/`enviadas` descartam o que vier duas vezes como criação.
  await listen<Item>("item", (event) => renderRecebido(event.payload));
  await listen<Enviado>("enviado", (event) => renderEnviado(event.payload));
  await listen<boolean>("presenca", (event) => atualizarPresenca(event.payload));

  // Só os recebidos da Sessão: a lista de Itens é em memória e morre com o
  // processo. A fila de saída é persistida e sobrevive, mas não é Sessão —
  // sair pela bandeja e reabrir tem que mostrar um chat vazio, com os
  // Pendentes ainda na fila, esperando o celular.
  for (const item of await invoke<Item[]>("list_items")) renderRecebido(item);

  ligarComposer();
  ligarDrop();
  ligarColar();
}

void main();
