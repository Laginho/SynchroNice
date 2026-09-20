// A janela do chat. Desenha cada Item recebido como bolha à esquerda ("o outro").
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { openUrl } from "@tauri-apps/plugin-opener";

interface Item {
  id: number;
  text: string;
  at_ms: number;
}

const scroll = document.querySelector<HTMLElement>(".scroll")!;
const empty = scroll.querySelector<HTMLElement>(".empty")!;
const seen = new Set<number>();

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

function render(item: Item): void {
  if (seen.has(item.id)) return;
  seen.add(item.id);
  empty.hidden = true;

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
  scroll.append(bubble);
  bubble.scrollIntoView({ block: "end" });
}

async function main(): Promise<void> {
  // Escuta antes de listar, para não perder um Item que chegue no meio;
  // `seen` descarta o que vier duas vezes.
  await listen<Item>("item", (event) => render(event.payload));
  for (const item of await invoke<Item[]>("list_items")) render(item);
}

void main();
