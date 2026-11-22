package top.witzzz.moonbazaar.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 问卷筛选/排序：提供商筛选是第一优先级，排序只作用于筛选结果。
 */
class SurveyFilterTest {

    private fun item(
        key: String,
        payout: Double,
        loi: Int,
        score: Int = -1,
        rating: Double = -1.0
    ) = SurveyItem(
        provider = key.uppercase(),
        providerKey = key,
        surveyId = "id-$key",
        reward = payout.toString(),
        loi = loi.toString(),
        url = "https://example.com/$key",
        score = score,
        ratingAvg = rating,
        payoutValue = payout,
        loiValue = loi
    )

    private val all = listOf(
        item("cpx", 10.0, 5, score = 80, rating = 2.5),
        item("prime", 30.0, 2, score = 100, rating = 4.5),
        item("zlexq", 5.0, 20),
        item("cherries", 20.0, 12)
    )

    @Test
    fun providerFilterKeepsOnlyThatProvider() {
        val out = filterAndSortSurveys(all, "prime", SurveySort.DEFAULT)
        assertEquals(1, out.size)
        assertEquals("prime", out.first().providerKey)
    }

    @Test
    fun providerFilterIsCaseInsensitive() {
        val out = filterAndSortSurveys(all, "CPX", SurveySort.DEFAULT)
        assertEquals(listOf("cpx"), out.map { it.providerKey })
    }

    @Test
    fun allKeepsEverythingInOrder() {
        val out = filterAndSortSurveys(all, "all", SurveySort.DEFAULT)
        assertEquals(all, out)
    }

    @Test
    fun payoutSortsDescending() {
        val out = filterAndSortSurveys(all, "all", SurveySort.PAYOUT)
        assertEquals(listOf(30.0, 20.0, 10.0, 5.0), out.map { it.payoutValue })
    }

    @Test
    fun loiSortsAscending() {
        val out = filterAndSortSurveys(all, "all", SurveySort.LOI)
        assertEquals(listOf(2, 5, 12, 20), out.map { it.loiValue })
    }

    @Test
    fun scoreSortsDescendingAndMissingLast() {
        val out = filterAndSortSurveys(all, "all", SurveySort.MATCH)
        assertEquals(listOf(100, 80, -1, -1), out.map { it.score })
    }

    @Test
    fun ratingSortsDescendingAndMissingLast() {
        val out = filterAndSortSurveys(all, "all", SurveySort.RATING)
        assertEquals(listOf(4.5, 2.5, -1.0, -1.0), out.map { it.ratingAvg })
    }

    @Test
    fun filterAppliesBeforeSort() {
        val out = filterAndSortSurveys(all, "cpx", SurveySort.PAYOUT)
        assertEquals(listOf("cpx"), out.map { it.providerKey })
    }

    @Test
    fun hasScoreAndRatingFlags() {
        val prime = all.first { it.providerKey == "prime" }
        val zlexq = all.first { it.providerKey == "zlexq" }
        assertEquals(true, prime.hasScore)
        assertEquals(true, prime.hasRating)
        assertEquals(false, zlexq.hasScore)
        assertEquals(false, zlexq.hasRating)
    }
}
