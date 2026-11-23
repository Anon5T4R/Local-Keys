# Junção de duas cópias do cofre ("Sincronizar com outra cópia")

Regras que o Android (`android/.../data/sync/VaultMerge.kt`) e o desktop seguem para juntar a cópia **atual**
(a aberta) com **outra** cópia do mesmo cofre. O formato do `.tkeys` não muda: nenhuma chave nova, nenhum
tipo novo. Os casos de teste comuns aos dois lados estão em `testdata/sync-merge/`.

## Regras

1. **Itens por `id`.** Itens de tipo desconhecido (`kind` que o app não conhece) entram na mesma regra, pelo JSON.
2. **De um lado só** → fica.
3. **Dos dois lados, iguais** (mesmo JSON, sem ligar para a ordem das chaves) → fica o atual.
4. **Dos dois lados, diferentes** → ganha o item inteiro de maior **tempo efetivo**:
   `max(updatedAt, deletedAt do item, deletedAt de cada anexo)` (ausente ou `null` = 0).
   Mandar item ou anexo para a lixeira grava só o `deletedAt`, sem mexer no `updatedAt` (como o oficial e o
   desktop fazem); por isso o `deletedAt` entra no tempo.
5. **Empate** no tempo efetivo com conteúdo diferente → fica o atual e conta como **conflito**.
6. O item vencedor vai **inteiro**: anexos (com o `dataB64`), passkeys, campos personalizados, histórico de
   senhas e chaves desconhecidas vêm dele. Nada é misturado campo a campo.
7. **Pastas**: união por `id`. Pasta dos dois lados → fica a do atual (nome e chaves). As pastas não têm
   `updatedAt`.
8. **Nível do cofre** (`version` e chaves desconhecidas fora de `folders`/`items`) → os do atual.
9. **Ordem**: itens do atual na ordem dele (com o vencedor no lugar), depois os que só existiam na outra cópia,
   na ordem dela; idem para pastas. A comparação dos testes é por `id` e não depende da ordem.
10. O resultado é gravado **nas duas cópias**; as duas passam a ser iguais.

## Resumo (o que a tela mostra antes de gravar)

Do ponto de vista do cofre atual: `added` (só na outra), `updated` (a outra era mais nova), `trashed` (a outra
mandou para a lixeira), `restored` (a outra tirou da lixeira), `conflicts` (empates), `foldersAdded`; e para a
outra cópia: `toOther` (itens que só existiam aqui ou aqui eram mais novos, inclusive os empates) e
`foldersToOther`. Cada caso de teste traz o seu `summary.json`.

## Limites do formato (não resolvidos sem mudar o formato)

- **Exclusão definitiva não deixa rastro.** Item ou pasta apagado de vez some do arquivo, sem tombstone. Se a
  outra cópia ainda o tem, ele **volta**. Para não voltar: apagar de vez nas duas cópias, ou sincronizar antes.
- **Restaurar da lixeira não grava hora.** Restaurar só zera o `deletedAt`. Contra uma cópia em que o item
  está na lixeira, o restaurado só ganha se foi editado depois (`updatedAt` maior que o `deletedAt` do outro);
  senão o item continua na lixeira.
- **Mudanças sem hora** (favoritar, apagar uma pasta e desvincular os itens, restaurar um anexo, apagar um
  anexo de vez) não mexem em `updatedAt`. Se só uma cópia mudou assim, os tempos empatam e fica o atual
  (conflito). Renomear pasta: fica o nome do atual.

## Casos de teste (`testdata/sync-merge/<caso>/`)

`a.json` é o cofre atual e `b.json` a outra cópia, em JSON puro (o conteúdo decifrado do `.tkeys`);
`expected.json` é o resultado e `summary.json` o resumo. A comparação de `expected.json` é pelo conteúdo:
`items` e `folders` por `id`, o resto igual.
