package com.localkeys.android.ui

import androidx.annotation.PluralsRes
import com.localkeys.android.R

/** Uma linha do resumo da junção: o plural (com a contagem) e quantos itens/pastas ela representa. */
data class MergeOtherLine(@PluralsRes val label: Int, val count: Int)

/**
 * O texto do resumo da junção de outro cofre, montado a partir das contagens da ponte — as mesmas linhas e a mesma
 * ordem do diálogo do desktop. Linha zerada não aparece; o "nada a juntar" é decidido antes (o resultado é o próprio
 * cofre aberto), como no desktop.
 */
object MergeOtherSummary {
    fun lines(counts: MergeOtherCounts): List<MergeOtherLine> = listOf(
        MergeOtherLine(R.plurals.merge_other_added, counts.added),
        MergeOtherLine(R.plurals.merge_other_duplicates, counts.collisions),
        MergeOtherLine(R.plurals.merge_other_already, counts.alreadyPresent),
        MergeOtherLine(R.plurals.merge_other_content_already, counts.contentAlreadyPresent),
        MergeOtherLine(R.plurals.merge_other_folders_added, counts.foldersAdded),
        MergeOtherLine(R.plurals.merge_other_folders_merged, counts.foldersMerged),
    ).filter { it.count > 0 }
}
