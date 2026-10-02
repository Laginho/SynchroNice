/** Entradas ainda não enviadas. Sem disco, DOM ou persistência. */
export interface Entrada<T> {
  readonly id: number;
  readonly origem: T;
  readonly falhou: boolean;
}

export class Bandeja<T> {
  private itens: Entrada<T>[] = [];
  private proximoId = 1;

  entradas(): readonly Entrada<T>[] {
    return this.itens.slice();
  }

  adicionar(origem: T): Entrada<T> {
    const entrada = { id: this.proximoId++, origem, falhou: false };
    this.itens.push(entrada);
    return entrada;
  }

  remover(id: number): void {
    this.itens = this.itens.filter((entrada) => entrada.id !== id);
  }
}
