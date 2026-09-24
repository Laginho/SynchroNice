export type TipoCopiavel = "cpf" | "cnpj" | "mobile" | "landline" | "email" | "pix";

export interface TrechoTexto {
  type: "text";
  text: string;
}

export interface TrechoLink {
  type: "link";
  text: string;
  href: string;
}

export interface TrechoCopiavel {
  type: "copyable";
  kind: TipoCopiavel;
  text: string;
  copyPlain: string;
  copyFormatted: string;
}

export type Trecho = TrechoTexto | TrechoLink | TrechoCopiavel;

const DDD_VALIDOS = new Set(
  `11 12 13 14 15 16 17 18 19
   21 22 24 27 28
   31 32 33 34 35 37 38
   41 42 43 44 45 46 47 48 49
   51 53 54 55
   61 62 63 64 65 66 67 68 69
   71 73 74 75 77 79
   81 82 83 84 85 86 87 88 89
   91 92 93 94 95 96 97 98 99`.trim().split(/\s+/),
);

const URL_SOURCE = String.raw`https?:\/\/[^\s<>"']+`;
const EMAIL_SOURCE = String.raw`[A-Z0-9.!#$%&'*+/=?^_\x60{|}~-]+@[A-Z0-9](?:[A-Z0-9-]*[A-Z0-9])?(?:\.[A-Z0-9](?:[A-Z0-9-]*[A-Z0-9])?)+`;
const UUID_SOURCE = String.raw`[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}`;
const CNPJ_FORMATADO_SOURCE = String.raw`\d{2}\.\d{3}\.\d{3}\/\d{4}-\d{2}`;
const CPF_FORMATADO_SOURCE = String.raw`\d{3}\.\d{3}\.\d{3}-\d{2}`;
const TELEFONE_SOURCE = String.raw`(?:\+?55[ \t]*)?(?:\(\d{2}\)|\d{2})[ \t.-]*(?:\d{4,5})[ \t.-]*\d{4}`;

// Um copiável termina em uma letra ou dígito: links têm pontuação final e
// seguem regra própria para não interpretar os números do endereço como ID.
const CANDIDATOS = new RegExp(
  String.raw`(?<![\p{L}\d])(?:(${URL_SOURCE})|(?:${EMAIL_SOURCE}|${UUID_SOURCE}|${CNPJ_FORMATADO_SOURCE}|${CPF_FORMATADO_SOURCE}|${TELEFONE_SOURCE}|\d{14})(?![\p{L}\d]))`,
  "giu",
);

/** Separa texto comum, links e dados válidos que podem ser copiados com um toque. */
export function segmentarTexto(texto: string): Trecho[] {
  const trechos: Trecho[] = [];
  let cursor = 0;

  for (const match of texto.matchAll(CANDIDATOS)) {
    const inicio = match.index;
    if (inicio === undefined) continue;

    const bruto = match[0];
    const urlBruta = match[1];
    const link = urlBruta ? criarTrechoLink(urlBruta) : null;
    const fimDoLink = link ? inicio + link.text.length : inicio;
    const copiavel = link ? null : criarTrechoCopiavel(bruto);
    const trecho = link ?? copiavel;
    if (!trecho) continue;

    const fim = link ? fimDoLink : inicio + bruto.length;
    adicionarTexto(trechos, texto.slice(cursor, inicio));
    trechos.push(trecho);
    cursor = fim;
  }

  adicionarTexto(trechos, texto.slice(cursor));
  if (trechos.length === 0) return [{ type: "text", text: texto }];
  return trechos;
}

function adicionarTexto(trechos: Trecho[], texto: string): void {
  if (!texto) return;
  const ultimo = trechos[trechos.length - 1];
  if (ultimo?.type === "text") ultimo.text += texto;
  else trechos.push({ type: "text", text: texto });
}

function criarTrechoLink(bruto: string): TrechoLink | null {
  const url = removerPontuacaoFinal(bruto);
  try {
    const endereco = new URL(url);
    if ((endereco.protocol !== "http:" && endereco.protocol !== "https:") || !endereco.hostname) {
      return null;
    }
    return { type: "link", text: url, href: url };
  } catch {
    return null;
  }
}

function removerPontuacaoFinal(url: string): string {
  let fim = url.length;
  while (fim > 0 && /[.,;:!?]/.test(url[fim - 1])) fim--;

  for (const [abertura, fechamento] of [["(", ")"], ["[", "]"], ["{", "}"]] as const) {
    while (
      fim > 0 &&
      url[fim - 1] === fechamento &&
      contar(url.slice(0, fim), fechamento) > contar(url.slice(0, fim), abertura)
    ) {
      fim--;
    }
  }
  return url.slice(0, fim);
}

function contar(texto: string, caractere: string): number {
  return [...texto].filter((c) => c === caractere).length;
}

function criarTrechoCopiavel(bruto: string): TrechoCopiavel | null {
  if (/^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]*[A-Z0-9])?(?:\.[A-Z0-9](?:[A-Z0-9-]*[A-Z0-9])?)+$/i.test(bruto)) {
    return criarCopiavel("email", bruto, bruto, bruto);
  }
  if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(bruto)) {
    return criarCopiavel("pix", bruto, bruto, bruto);
  }

  const digitos = bruto.replace(/\D/g, "");
  const telefone = formatarTelefone(digitos);
  if (telefone) return criarCopiavel(telefone.kind, telefone.exibido, telefone.digitos, telefone.exibido);

  if (digitos.length === 11 && validarCpf(digitos)) {
    const exibido = `${digitos.slice(0, 3)}.${digitos.slice(3, 6)}.${digitos.slice(6, 9)}-${digitos.slice(9)}`;
    return criarCopiavel("cpf", exibido, digitos, exibido);
  }

  if (digitos.length === 14 && validarCnpj(digitos)) {
    const exibido = `${digitos.slice(0, 2)}.${digitos.slice(2, 5)}.${digitos.slice(5, 8)}/${digitos.slice(8, 12)}-${digitos.slice(12)}`;
    return criarCopiavel("cnpj", exibido, digitos, exibido);
  }
  return null;
}

function criarCopiavel(kind: TipoCopiavel, text: string, copyPlain: string, copyFormatted: string): TrechoCopiavel {
  return { type: "copyable", kind, text, copyPlain, copyFormatted };
}

function formatarTelefone(digitosComPais: string): { kind: "mobile" | "landline"; digitos: string; exibido: string } | null {
  const digitos =
    (digitosComPais.length === 12 || digitosComPais.length === 13) && digitosComPais.startsWith("55")
      ? digitosComPais.slice(2)
      : digitosComPais;
  const ddd = digitos.slice(0, 2);
  if (!DDD_VALIDOS.has(ddd)) return null;

  if (digitos.length === 11 && digitos[2] === "9") {
    return { kind: "mobile", digitos, exibido: `(${ddd}) ${digitos.slice(2, 7)}-${digitos.slice(7)}` };
  }
  if (digitos.length === 10 && /^[2-5]$/.test(digitos[2])) {
    return { kind: "landline", digitos, exibido: `(${ddd}) ${digitos.slice(2, 6)}-${digitos.slice(6)}` };
  }
  return null;
}

function validarCpf(digitos: string): boolean {
  if (!/^\d{11}$/.test(digitos) || /^(\d)\1{10}$/.test(digitos)) return false;
  const base = [...digitos].map(Number);
  const calcular = (quantidade: number, pesoInicial: number): number => {
    const soma = base.slice(0, quantidade).reduce((total, digito, i) => total + digito * (pesoInicial - i), 0);
    const resto = (soma * 10) % 11;
    return resto === 10 ? 0 : resto;
  };
  return calcular(9, 10) === base[9] && calcular(10, 11) === base[10];
}

function validarCnpj(digitos: string): boolean {
  if (!/^\d{14}$/.test(digitos) || /^(\d)\1{13}$/.test(digitos)) return false;
  const base = [...digitos].map(Number);
  const calcular = (quantidade: number, pesos: number[]): number => {
    const soma = base.slice(0, quantidade).reduce((total, digito, i) => total + digito * pesos[i], 0);
    const resto = soma % 11;
    return resto < 2 ? 0 : 11 - resto;
  };
  const primeiro = calcular(12, [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]);
  const segundo = calcular(13, [6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]);
  return primeiro === base[12] && segundo === base[13];
}
