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
- **Item sem `id` (JSON corrompido).** Cópias reais nunca têm um, mas se tiver: o desktop **mantém** o do cofre
  atual (conservador) e o Android o **descarta** do resultado.

## Casos de teste (`testdata/sync-merge/<caso>/`)

`a.json` é o cofre atual e `b.json` a outra cópia, em JSON puro (o conteúdo decifrado do `.tkeys`);
`expected.json` é o resultado e `summary.json` o resumo. A comparação de `expected.json` é pelo conteúdo:
`items` e `folders` por `id`, o resto igual.

## Juntar um cofre DIFERENTE (pedido 027, unidade 7)

Juntar outro `.tkeys` no aberto não é sincronizar: são cofres distintos, não existe "o mais novo", o outro cofre é
**somente leitura** (a sincronização grava nos dois; esta não grava no outro) e o resultado é um cofre novo que só é
gravado depois do resumo confirmado. A regra vive em `crates/core/src/merge_other.rs` (desktop) e na ponte
`merge_other_vault` (Android).

- Item com id só no outro cofre → entra **inteiro**, com o mesmo id (anexos, passkeys, histórico e chaves
  desconhecidas vêm juntos).
- Mesmo id e **conteúdo igual** → nada a fazer ("já estava"). Para toda chave, em qualquer nível, **chave ausente e
  `null` valem o mesmo** — o modelo do Android grava `folderId`/`deletedAt` como `null` onde o desktop omite a chave,
  e a normalização é global (não só nesses dois campos).
- Mesmo id e **conteúdo diferente** → ficam os dois: o que entra ganha um **id novo** (o do aberto não muda). Se o
  conteúdo do outro já existe no aberto sob OUTRO id — a duplicata de uma junção anterior **ou uma edição que ficou
  idêntica a outro item** —, não entra de novo e conta em `content_already_present` ("já estava com outro id"). O
  resumo não distingue os dois casos, e a edição idêntica é descartada.
- Item na lixeira do outro entra como está (na lixeira). `updatedAt` de nada que entra muda (carimbar é do chamador).
- Pastas: mesmo id → a do aberto; id diferente com o **mesmo nome** → une (os itens da pasta do outro repontam para a
  do aberto, e as chaves desconhecidas da pasta do outro que a do aberto não tem entram nela — as do aberto vencem).
  A pasta conta em `folders_merged` quando algum item foi reapontado **ou** alguma chave da pasta do outro entrou.
  Senão a pasta entra inteira (com as chaves que o app não conhece). Pasta órfã de um item é preservada.
- Só `folders` e `items` do outro entram; a raiz (versão, chaves desconhecidas) é a do aberto.
- **Cofre malformado** (sem `items`/`folders` em lista), **item ou pasta que não é objeto** e **item ou pasta sem `id`
  (ou com `id` vazio)**, em qualquer dos dois cofres, recusam a junção inteira — nada é montado com uma entrada
  inválida.
- Resumo antes de confirmar: itens adicionados, colisões (duplicados com id novo), já presentes, já presentes com
  outro id, pastas adicionadas e pastas unidas.

As fixtures dessa regra ficam ao lado das de sincronização, com o prefixo `other-`:
`other-<caso>-{base,other,expected,summary}.json`. O id que a regra gera aparece no esperado como `"<new>"` (o runner
liga ao id real); os dois lados rodam as mesmas fixtures.
