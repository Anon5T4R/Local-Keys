package com.localkeys.android.ui

import com.localkeys.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O texto do resumo da junção: as linhas saem na ordem do desktop, com o plural certo, e a linha zerada não aparece
 * (o "nada a juntar" é decidido antes, quando o resultado é o próprio cofre aberto).
 */
class MergeOtherSummaryTest {

    @Test
    fun `every non-zero count becomes a line in the desktop order`() {
        val lines = MergeOtherSummary.lines(
            MergeOtherCounts(added = 2, collisions = 1, alreadyPresent = 3, contentAlreadyPresent = 4, foldersAdded = 5, foldersMerged = 6),
        )
        assertEquals(
            listOf(
                MergeOtherLine(R.plurals.merge_other_added, 2),
                MergeOtherLine(R.plurals.merge_other_duplicates, 1),
                MergeOtherLine(R.plurals.merge_other_already, 3),
                MergeOtherLine(R.plurals.merge_other_content_already, 4),
                MergeOtherLine(R.plurals.merge_other_folders_added, 5),
                MergeOtherLine(R.plurals.merge_other_folders_merged, 6),
            ),
            lines,
        )
    }

    @Test
    fun `zero counts are left out`() {
        val lines = MergeOtherSummary.lines(MergeOtherCounts(added = 1, alreadyPresent = 2))
        assertEquals(listOf(MergeOtherLine(R.plurals.merge_other_added, 1), MergeOtherLine(R.plurals.merge_other_already, 2)), lines)
    }

    @Test
    fun `nothing to count means no lines`() {
        assertTrue(MergeOtherSummary.lines(MergeOtherCounts()).isEmpty())
    }
}
