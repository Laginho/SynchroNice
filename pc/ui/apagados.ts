/** Exclusões definitivas e seleções que aguardam o resultado de Apagar tudo.
 * Eventos e snapshots atrasados não recriam essas bolhas; uma seleção que
 * falhou volta a aparecer, sem desfazer exclusões confirmadas pelo celular. */
export class Apagados {
  private recebidos = new Set<number>();
  private enviados = new Set<number>();
  private pendentes = new Set<{ recebidos: Set<number>; enviados: Set<number> }>();

  iniciar(recebidos: readonly number[], enviados: readonly number[]): {
    confirmar: () => void;
    cancelar: () => void;
  } {
    const selecao = { recebidos: new Set(recebidos), enviados: new Set(enviados) };
    this.pendentes.add(selecao);
    return {
      confirmar: () => {
        if (this.pendentes.delete(selecao)) {
          this.marcar([...selecao.recebidos], [...selecao.enviados]);
        }
      },
      cancelar: () => { this.pendentes.delete(selecao); },
    };
  }

  marcar(recebidos: readonly number[], enviados: readonly number[]): void {
    for (const id of recebidos) this.recebidos.add(id);
    for (const id of enviados) this.enviados.add(id);
  }

  recebido(id: number): boolean {
    return this.recebidos.has(id) || [...this.pendentes].some((selecao) => selecao.recebidos.has(id));
  }

  enviado(id: number): boolean {
    return this.enviados.has(id) || [...this.pendentes].some((selecao) => selecao.enviados.has(id));
  }

  filtrarRecebidos<T extends { id: number }>(itens: readonly T[]): T[] {
    return itens.filter((item) => !this.recebido(item.id));
  }

  filtrarEnviados<T extends { id: number }>(itens: readonly T[]): T[] {
    return itens.filter((item) => !this.enviado(item.id));
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
