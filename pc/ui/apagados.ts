/** Ids que a janela já sabe excluídos (Apagar tudo aplicado ou exclusão do
 * celular). Os ids de uma Sessão nunca se repetem, então a marca é definitiva:
 * eventos e snapshots atrasados não recriam essas bolhas. */
export class Apagados {
  private recebidos = new Set<number>();
  private enviados = new Set<number>();

  marcar(recebidos: readonly number[], enviados: readonly number[]): void {
    for (const id of recebidos) this.recebidos.add(id);
    for (const id of enviados) this.enviados.add(id);
  }

  recebido(id: number): boolean {
    return this.recebidos.has(id);
  }

  enviado(id: number): boolean {
    return this.enviados.has(id);
  }

  filtrarRecebidos<T extends { id: number }>(itens: readonly T[]): T[] {
    return itens.filter((item) => !this.recebidos.has(item.id));
  }

  filtrarEnviados<T extends { id: number }>(itens: readonly T[]): T[] {
    return itens.filter((item) => !this.enviados.has(item.id));
  }
}

export type Escutar = (
  evento: string,
  tratar: (event: { payload: unknown }) => void,
) => Promise<unknown>;

/** Registra "apagado" antes de "item" e "enviado": cada registro é assíncrono, e
 * um Item que chega e é excluído entre dois deles não pode deixar uma bolha que
 * ninguém remove. */
export async function ligarOuvintes<R, E, X>(
  escutar: Escutar,
  ouvintes: {
    apagado: (excluidos: X) => void;
    item: (recebido: R) => void;
    enviado: (enviado: E) => void;
  },
): Promise<void> {
  await escutar("apagado", (event) => ouvintes.apagado(event.payload as X));
  await escutar("item", (event) => ouvintes.item(event.payload as R));
  await escutar("enviado", (event) => ouvintes.enviado(event.payload as E));
}
