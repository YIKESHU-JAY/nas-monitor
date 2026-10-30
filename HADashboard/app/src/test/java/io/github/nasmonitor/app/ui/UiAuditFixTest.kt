package io.github.nasmonitor.app.ui

import io.github.nasmonitor.app.NewApiModelUsage
import io.github.nasmonitor.app.NewApiUsageModel
import io.github.nasmonitor.app.NewApiUsageAccount
import io.github.nasmonitor.app.NewApiUsageWindow
import io.github.nasmonitor.app.NewApiUsageHourly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiAuditFixTest {
    @Test
    fun chartBoundsAndTicksShareTheSameUpperBound() {
        for (rawMaxY in listOf(0f, 1f, 3f, 3.1f, 17f, 60f, 101f)) {
            val normalizedMaxY = chartNiceMaxY(rawMaxY)
            val ticks = chartTicks(rawMaxY)
            assertTrue(normalizedMaxY >= 1f)
            assertEquals(normalizedMaxY, ticks.last(), 0.0001f)
            assertEquals(6, ticks.size)
            assertEquals(0f, ticks.first(), 0.0001f)
            assertTrue(ticks.zipWithNext().all { (previousTick, nextTick) -> nextTick >= previousTick })
        }
    }

    @Test
    fun chartNiceBoundsCoverRepresentativeTrafficAndTemperatureRanges() {
        assertEquals(3f, chartNiceMaxY(3f), 0.0001f)
        assertEquals(20f, chartNiceMaxY(17f), 0.0001f)
        assertEquals(60f, chartNiceMaxY(60f), 0.0001f)
        assertEquals(120f, chartNiceMaxY(101f), 0.0001f)
    }

    @Test
    fun trendGapsMarkOnlyIntervalsLongerThanThreshold() {
        val times = listOf(0.0, 10.0, 20.0, 100.0, 110.0)
        val gaps = trendGaps(times, 25.0)
        assertEquals(1, gaps.size)
        assertEquals((20.0 / 110.0).toFloat(), gaps[0].startFraction, 0.0001f)
        assertEquals((100.0 / 110.0).toFloat(), gaps[0].endFraction, 0.0001f)
    }

    @Test
    fun trendGapsIgnoreUnorderedOrTooShortSeries() {
        assertTrue(trendGaps(listOf(10.0), 25.0).isEmpty())
        assertTrue(trendGaps(listOf(10.0, 10.0), 25.0).isEmpty())
        assertTrue(trendGaps(listOf(0.0, 10.0, 20.0), 25.0).isEmpty())
    }

    @Test
    fun airportProgressUsesUsedPercentAndNeverLeavesIndicatorRange() {
        assertEquals(0f, airportUsedProgress(0.0), 0.0001f)
        assertEquals(0.5f, airportUsedProgress(50.0), 0.0001f)
        assertEquals(1f, airportUsedProgress(120.0), 0.0001f)
        assertEquals(50.0, airportRemainingPercent(50.0), 0.0001)
        assertEquals(0.0, airportRemainingPercent(120.0), 0.0001)
    }

    @Test
    fun workBuddyCompactBalancePreservesBothNumbersAndMissingValues() {
        assertEquals("3,554 / 3,037", workBuddyCompactBalanceLabel(3554, 3037))
        assertEquals("3,554 · 基准缺失", workBuddyCompactBalanceLabel(3554, null))
        assertEquals("积分未知", workBuddyCompactBalanceLabel(null, 3037))
        assertEquals("3,554 / 0（不计算比例）", workBuddyCompactBalanceLabel(3554, 0))
    }

    @Test
    fun workBuddyBalanceNamesBothValuesAndHandlesMissingBaseline() {
        assertEquals("当前余额 3,554 · 当日基准 3,037", workBuddyBalanceLabel(3554, 3037))
        assertEquals("当前余额 3,554 · 当日基准缺失", workBuddyBalanceLabel(3554, null))
        assertEquals("当前余额未知 · 当日基准 3,037", workBuddyBalanceLabel(null, 3037))
        assertEquals("当前余额 3,554 · 当日基准 0（不计算比例）", workBuddyBalanceLabel(3554, 0))
    }

    @Test
    fun workBuddyRatioNamesBaselineWithoutImplyingAQuotaCeiling() {
        assertEquals("余额为当日基准的 117%", workBuddyRatioLabel(1.17f))
        assertEquals("余额为当日基准的 128%", workBuddyRatioLabel(1.28f))
        assertEquals("余额为当日基准的 80%", workBuddyRatioLabel(0.8f))
        assertEquals("余额为当日基准的 0%", workBuddyRatioLabel(-0.1f))
        assertEquals(1f, gatewayProgress(1.28f), 0.0001f)
    }

    @Test
    fun gatewayOverageKeepsSourceMeaningWhileClampingProgress() {
        assertEquals(1f, gatewayProgress(1.01f), 0.0001f)
        assertEquals("剩余 101%（已超出月初余额 1%）", gatewayRemainingLabel(1.01f, "月初余额"))
        assertEquals("剩余 101%（已超出当日基准 1%）", gatewayRemainingLabel(1.01f, "当日基准"))
        assertEquals("剩余 80%", gatewayRemainingLabel(0.8f))
    }

    @Test
    fun overviewDailyTokenUsesUsageOneDayWindow() {
        val usage = NewApiModelUsage(
            windows = mapOf(
                "1d" to NewApiUsageWindow(
                    models = listOf(
                        NewApiUsageModel(tokens = 1_234),
                        NewApiUsageModel(tokens = 2_345)
                    )
                )
            )
        )
        assertEquals("3,579", overviewDailyTokenLabel(usage))
        assertEquals("--", overviewDailyTokenLabel(null))
    }

    @Test
    fun oneDayTrendUsesLatestTwelveHoursAndFillsEmptyHours() {
        val hourly = listOf(
            NewApiUsageHourly(hourStart = "2026-10-22T10:00:00", date = "2026-10-22", hour = 10, requests = 2, tokens = 20),
            NewApiUsageHourly(hourStart = "2026-10-22T13:00:00", date = "2026-10-22", hour = 13, requests = 3, tokens = 30),
            NewApiUsageHourly(hourStart = "2026-10-21T23:00:00", date = "2026-10-21", hour = 23, requests = 99, tokens = 990)
        )
        val points = usageTrendPoints(emptyList(), hourly, "1d", "2026-10-22")
        assertEquals(12, points.size)
        assertEquals("02:00", points.first().label)
        assertEquals("13:00", points.last().label)
        assertEquals(0, points.first().requests)
        assertEquals(3, points.last().requests)
    }

    @Test
    fun oneDayTrendShowsSeveralHourlyAxisLabels() {
        val count = usageTrendTickCount("1d", 12, 320f)
        val indices = usageTrendTickIndices("1d", 12, 320f)
        assertTrue(count in 6..7)
        assertEquals(count, indices.size)
        assertEquals(0, indices.first())
        assertEquals(11, indices.last())
    }

    @Test
    fun usageAxisTicksCoverBothSeriesWithReadableValues() {
        val requests = usageAxisTicks(1_565)
        val tokens = usageAxisTicks(238_967_422)
        assertEquals(5, requests.size)
        assertEquals(5, tokens.size)
        assertTrue(requests.last() >= 1_565)
        assertTrue(tokens.last() >= 238_967_422)
        assertEquals("0", formatUsageAxis(0))
        assertEquals("1.5k", formatUsageAxis(1_500))
        assertEquals("24万", formatUsageAxis(238_900))
        assertEquals("2.4亿", formatUsageAxis(238_967_422))
    }

    @Test
    fun usageAccountsSortByRequestsThenNameAndKeepUnknownReadable() {
        val accounts = listOf(
            NewApiUsageAccount(accountId = "2", accountName = "huangtao", requests = 7, tokens = 70),
            NewApiUsageAccount(accountId = "1", accountName = "root", requests = 70, tokens = 700),
            NewApiUsageAccount(accountId = "3", accountName = "", requests = 7, tokens = 70)
        )
        val sorted = usageAccountSort(accounts)
        assertEquals(listOf("root", "huangtao", "未知账号"), sorted.map { usageAccountDisplayName(it.accountName) })
        assertEquals("未知账号", usageAccountDisplayName("   "))
    }

    @Test
    fun usageAccountsStayInsideSelectedWindow() {
        val usage = NewApiModelUsage(
            windows = mapOf(
                "1d" to NewApiUsageWindow(accounts = listOf(NewApiUsageAccount(accountId = "1", accountName = "root"))),
                "30d" to NewApiUsageWindow(accounts = listOf(
                    NewApiUsageAccount(accountId = "1", accountName = "root"),
                    NewApiUsageAccount(accountId = "2", accountName = "huangtao")
                ))
            )
        )
        assertEquals(listOf("root"), usage.windows["1d"]?.accounts?.map { it.accountName })
        assertEquals(listOf("root", "huangtao"), usage.windows["30d"]?.accounts?.map { it.accountName })
    }

    @Test
    fun usageAccountLongNamesRemainCompleteAndSafeToDisplay() {
        val longName = "huangtao-very-long-account-name-for-layout-safety"
        assertEquals(longName, usageAccountDisplayName(longName))
        assertTrue(usageAccountDisplayName(longName).isNotBlank())
    }
}
