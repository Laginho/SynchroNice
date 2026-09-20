// A janela do chat. Desenha cada Item recebido como bolha à esquerda ("o outro").
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
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

const IMAGEM = new Set(["jpg", "jpeg", "png", "gif", "webp", "bmp"]);

const scroll = document.querySelector<HTMLElement>(".scroll")!;
const empty = scroll.querySelector<HTMLElement>(".empty")!;
/** Bolha já desenhada por id, para atualizar progresso em vez de duplicar. */
const bolhas = new Map<number, HTMLElement>();

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

function renderTexto(item: TextItem): HTMLElement {
  const bubble = document.createElement("div");
  bubble.className = "item";

  // textContent, nunca innerHTML: HTML recebido aparece como texto literal.
  const body = document.createElement("div");
  body.className = "text";
  const url = asUrl(item.text);
  if (url) {
    const link = document.createElement("a");
    link.href = url;
    link.textContent = item.text;
    link.addEventListener("click", (event) => {
      event.preventDefault();
      void openUrl(url);
    });
    body.append(link);
  } else {
    body.textContent = item.text;
  }

  const time = document.createElement("time");
  time.textContent = hora(item.at_ms);

  bubble.append(body, time);
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

function render(item: Item): void {
  const existente = bolhas.get(item.id);
  if (existente) {
    // Só um arquivo em andamento gera evento repetido para o mesmo id.
    if (item.kind === "file") atualizarProgresso(existente, item);
    return;
  }

  empty.hidden = true;
  const bubble = item.kind === "text" ? renderTexto(item) : renderArquivo(item);
  bolhas.set(item.id, bubble);
  scroll.append(bubble);
  bubble.scrollIntoView({ block: "end" });
}

async function main(): Promise<void> {
  // Escuta antes de listar, para não perder um Item que chegue no meio;
  // `bolhas` descarta o que vier duas vezes como criação.
  await listen<Item>("item", (event) => render(event.payload));
  for (const item of await invoke<Item[]>("list_items")) render(item);
}

void main();
