/** Entradas ainda não enviadas. Sem disco, DOM ou persistência. */
export interface Entrada<T> {
  readonly id: number;
  readonly origem: T;
  readonly falhou: boolean;
}

export type Envio<T> =
  | { tipo: "entrada"; entrada: Entrada<T> }
  | { tipo: "texto"; texto: string };

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

  sequencia(texto: string): Envio<T>[] {
    const envios: Envio<T>[] = this.itens.map((entrada) => ({ tipo: "entrada", entrada }));
    const legenda = texto.trim();
    if (legenda) envios.push({ tipo: "texto", texto: legenda });
    return envios;
  }
}
