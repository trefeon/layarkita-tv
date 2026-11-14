package com.layarkita.tv.ui.screens.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for the player surface caption geometry contract.
 *
 * [PlayerSubtitleBottomFraction] is a compile-time constant (inlined at use
 * sites, so no Compose runtime is needed to read it): captions sit at a fixed
 * fraction of SCREEN height in an overlay outside the zoomed content frame,
 * which is what makes ZOOM safe on 21:9 with no subtitle adjustment. This test
 * pins that fraction so a casual tweak cannot silently push captions under
 * the letterbox or into the picture.
 */
class PlayerSurfaceHostTest {

    @Test
    fun captionBottomPaddingIsEightPercentOfScreenHeight() {
        assertEquals(0.08f, PlayerSubtitleBottomFraction, 0.0f)
    }
}
