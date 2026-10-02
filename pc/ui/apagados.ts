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
