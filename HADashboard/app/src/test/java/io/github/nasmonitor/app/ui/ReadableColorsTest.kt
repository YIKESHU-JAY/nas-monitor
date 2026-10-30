package io.github.nasmonitor.app.ui

import androidx.compose.ui.graphics.Color
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadableColorsTest {
    private val nonGlassThemes = listOf("light", "dark", "purple", "aurora", "sunset")

    @After
    fun resetTheme() {
        applyTheme("light")
        setAccentColor("#5B7CFA")
    }

    private fun cardSurface(): Color = surfaceOnBackdrop(CardSurface)
    private fun emphasisSurface(): Color = surfaceOnBackdrop(EmphasisSurface)

    private fun assertReadable(label: String, foreground: Color, background: Color) {
        val ratio = contrastRatio(foreground, background)
        assertTrue("$label ratio=$ratio", ratio >= 4.5f)
    }

    @Test
    fun everyNonGlassThemeKeepsTextAndStatusReadableOnCards() {
        for (theme in nonGlassThemes) {
            applyTheme(theme)
            for ((name, accent) in AccentOptions) {
                setAccentColor(accent.hex())
                val surface = cardSurface()
                val emphasis = emphasisSurface()
                for (background in listOf(surface, emphasis)) {
                    assertReadable("$theme/$name Ink", Ink, background)
                    assertReadable("$theme/$name Muted", Muted, background)
                    assertReadable("$theme/$name Subtle", Subtle, background)
                    assertReadable("$theme/$name Green", Green, background)
                    assertReadable("$theme/$name Amber", Amber, background)
                    assertReadable("$theme/$name Red", Red, background)
                    assertReadable("$theme/$name Blue text", Blue, background)
                    assertReadable("$theme/$name OnAccentSoft", OnAccentSoft, background)
                }
                assertReadable("$theme/$name OnAccent", OnAccent, AccentFill)
                assertReadable("$theme/$name StatusSummary", StatusSummaryInk, StatusSummarySurface)
                assertReadable("$theme/$name StatusHealthy", StatusHealthyInk, StatusHealthySurface)
                assertReadable("$theme/$name StatusAttention", StatusAttentionInk, StatusAttentionSurface)
                assertReadable("$theme/$name StatusCritical", StatusCriticalInk, StatusCriticalSurface)
            }
        }
    }

    @Test
    fun cardSurfacesExposeEightToTwelvePercentAndEmphasisFourToEightPercent() {
        for (theme in nonGlassThemes) {
            applyTheme(theme)
            val normalExposure = 1f - CardSurface.alpha
            val emphasisExposure = 1f - EmphasisSurface.alpha
            assertTrue("$theme normal exposure=$normalExposure",
                normalExposure >= 0.08f && normalExposure <= 0.12f)
            assertTrue("$theme emphasis exposure=$emphasisExposure",
                emphasisExposure >= 0.04f && emphasisExposure <= 0.08f)
        }
    }

    @Test
    fun floatingSurfacesStayOpaqueInNonGlassThemes() {
        for (theme in nonGlassThemes) {
            applyTheme(theme)
            for ((name, color) in listOf(
                "overlay" to OverlaySurface,
                "overlayVariant" to OverlayVariant,
                "navigation" to NavigationSurface,
                "terminal" to TerminalBg
            )) {
                assertEquals("$theme $name alpha", 1f, color.alpha)
            }
        }
    }

    @Test
    fun glassThemeKeepsOriginalCardBranch() {
        applyTheme("glass")
        // Glass keeps its original translucent palette and does not use CardSurface.
        assertEquals(0.8f, CardWhite.alpha, 0.01f)
        assertEquals(0.8f, CardSurface.alpha, 0.01f)
        assertEquals(0.5f, EmphasisSurface.alpha, 0.01f)
    }

    @Test
    fun nonGlassCardDefaultsPreserveSemanticColorsAndTranslucencyForOrdinaryCards() {
        for (theme in nonGlassThemes) {
            applyTheme(theme)
            val semanticColors = listOf(
                "summary" to StatusSummarySurface,
                "healthy" to StatusHealthySurface,
                "attention" to StatusAttentionSurface,
                "critical" to StatusCriticalSurface,
                "blueSoft" to BlueSoft,
                "greenSoft" to GreenSoft,
                "amberSoft" to AmberSoft,
                "redSoft" to RedSoft
            )
            for ((name, semantic) in semanticColors) {
                assertTrue("$theme/$name semantic color must remain distinct",
                    semantic != CardWhite && semantic != CardSurface)
                assertEquals("$theme/$name default keeps caller colors", false,
                    resolveNonGlassTranslucency(requestedIsOrdinaryCard = false, translucentSurface = null))
            }
            assertEquals(
                "$theme default ordinary card stays translucent",
                true,
                resolveNonGlassTranslucency(requestedIsOrdinaryCard = true, translucentSurface = null)
            )
            assertEquals(
                "$theme explicit transparency opts in even with semantic colors",
                true,
                resolveNonGlassTranslucency(requestedIsOrdinaryCard = false, translucentSurface = true)
            )
            assertEquals(
                "$theme explicit opaque card is preserved",
                false,
                resolveNonGlassTranslucency(requestedIsOrdinaryCard = true, translucentSurface = false)
            )
        }
    }

    @Test
    fun ordinaryAlertCardsUseTranslucentSurfaceWithoutElevationTint() {
        for (theme in nonGlassThemes) {
            applyTheme(theme)
            val exposure = 1f - CardSurface.alpha
            assertTrue("$theme ordinary alert card must remain translucent: $exposure",
                exposure >= 0.08f && exposure <= 0.12f)
            assertEquals("$theme ordinary alert card must not tint with elevation", 0f, ALERT_CARD_ELEVATION.value, 0.0001f)
            assertEquals("$theme shared ordinary card elevation", 0f, NON_GLASS_CARD_ELEVATION.value, 0.0001f)
        }
    }

    @Test
    fun sharedFrostCardPolicyKeepsTranslucentSurfaceWithoutElevation() {
        for (theme in nonGlassThemes) {
            applyTheme(theme)
            assertTrue("$theme frost card surface must remain translucent", CardSurface.alpha < 1f)
            assertEquals("$theme frost card must not add elevation tint", 0f, NON_GLASS_CARD_ELEVATION.value, 0.0001f)
        }
        applyTheme("glass")
        assertTrue("glass must keep its original translucency", CardWhite.alpha < 1f)
    }

    @Test
    fun nestedSurfacesAreOpaqueAndKeepSemanticColors() {
        for (theme in nonGlassThemes) {
            applyTheme(theme)
            assertTrue("$theme InnerSurface must be opaque", InnerSurface.alpha == 1f)
            assertTrue("$theme InnerVariant must be opaque", InnerVariant.alpha == 1f)
            for ((name, pair) in listOf(
                "healthy" to (StatusHealthySurface to StatusHealthyInk),
                "attention" to (StatusAttentionSurface to StatusAttentionInk),
                "critical" to (StatusCriticalSurface to StatusCriticalInk),
                "summary" to (StatusSummarySurface to StatusSummaryInk)
            )) {
                val (semantic, semanticInk) = pair
                if (name != "summary") {
                    assertTrue("$theme $name must stay opaque", semantic.alpha == 1f)
                }
                assertReadable("$theme $name nested ink", semanticInk, semantic)
            }
            assertReadable("$theme InnerSurface Muted", Muted, InnerSurface)
            assertReadable("$theme InnerSurface Ink", Ink, InnerSurface)
        }
    }
}
