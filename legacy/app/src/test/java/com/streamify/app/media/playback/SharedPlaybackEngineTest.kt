package com.streamify.app.media.playback

import androidx.media3.common.Player
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SharedPlaybackEngineTest (MEDIA-01) — hermetic JVM proof of the hardened
 * engine holder lifecycle:
 *
 *  • publish/withdraw identity guard (a late stale withdraw must not yank
 *    a freshly re-published engine),
 *  • clear-BEFORE-notify withdraw ordering (listener callbacks re-reading
 *    the holder observe null — they can never re-grab the dying engine),
 *  • cold-start latch: a listener registered while the engine is already
 *    published fires immediately; one registered before publication fires
 *    on publish,
 *  • publication generation monotonicity,
 *  • listener removal.
 *
 * The Player instances are java.lang.reflect.Proxy fakes — the engine only
 * ever compares identity, so no Player method is invoked and no host
 * artifact is needed. Runs in the plain jvm-unit-suite CI shard.
 */
class SharedPlaybackEngineTest {

    /**
     * Identity-only Player fake. The engine never invokes Player methods —
     * it only compares references — but java.lang.reflect.Proxy routes
     * equals()/hashCode()/toString() through the handler, so those MUST be
     * identity-correct or assertEquals(player, player) fails on the very
     * same instance. Everything else gets type-safe defaults.
     */
    private fun fakePlayer(): Player = java.lang.reflect.Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "FakePlayer@" + Integer.toHexString(System.identityHashCode(proxy))
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Double.TYPE -> 0.0
                java.lang.Float.TYPE -> 0f
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Character.TYPE -> ' '
                else -> null
            }
        }
    } as Player

    private class RecordingListener : SharedPlaybackEngine.EngineListener {
        var publishedCount = 0
        var withdrawnCount = 0
        var lastPublished: Player? = null
        var playerVisibleInsideWithdrawCallback: Player? = null

        override fun onEnginePublished(player: Player) {
            publishedCount++
            lastPublished = player
        }

        override fun onEngineWithdrawn() {
            withdrawnCount++
            // Re-read the holder exactly like a real consumer would before
            // dropping its session: MUST observe null (clear-before-notify).
            playerVisibleInsideWithdrawCallback = SharedPlaybackEngine.player
        }
    }

    @After
    fun tearDown() {
        // Return the holder to a pristine state for any other test class on
        // the shared JVM: withdraw whatever is published (identity guard
        // makes a wrong guess a no-op).
        SharedPlaybackEngine.player?.let { SharedPlaybackEngine.withdraw(it) }
    }

    @Test
    fun `withdraw is identity guarded - a stale instance cannot yank a fresh engine`() {
        val first = fakePlayer()
        val second = fakePlayer()

        SharedPlaybackEngine.publish(first)
        SharedPlaybackEngine.publish(second)
        // Late callback from the FIRST (already superseded) service instance:
        SharedPlaybackEngine.withdraw(first)

        assertNotNull("stale withdraw must not clear the fresh engine", SharedPlaybackEngine.player)
        assertEquals(second, SharedPlaybackEngine.player)

        // The rightful owner still can:
        SharedPlaybackEngine.withdraw(second)
        assertNull(SharedPlaybackEngine.player)
    }

    @Test
    fun `withdraw clears the reference BEFORE notifying listeners`() {
        val listener = RecordingListener()
        SharedPlaybackEngine.addEngineListener(listener)

        val engine = fakePlayer()
        SharedPlaybackEngine.publish(engine)
        SharedPlaybackEngine.withdraw(engine)

        assertEquals(1, listener.withdrawnCount)
        assertNull(
            "listener must observe a cleared holder inside the callback",
            listener.playerVisibleInsideWithdrawCallback
        )
        assertNull(SharedPlaybackEngine.player)
    }

    @Test
    fun `listener registered after publication fires immediately - cold start latch`() {
        val engine = fakePlayer()
        SharedPlaybackEngine.publish(engine)

        val lateListener = RecordingListener()
        SharedPlaybackEngine.addEngineListener(lateListener)

        assertEquals(
            "registration after publish must fire synchronously",
            1,
            lateListener.publishedCount
        )
        assertEquals(engine, lateListener.lastPublished)
    }

    @Test
    fun `listener registered before publication fires on publish`() {
        val earlyListener = RecordingListener()
        SharedPlaybackEngine.addEngineListener(earlyListener)
        assertEquals(0, earlyListener.publishedCount)

        val engine = fakePlayer()
        SharedPlaybackEngine.publish(engine)

        assertEquals(1, earlyListener.publishedCount)
        assertEquals(engine, earlyListener.lastPublished)
    }

    @Test
    fun `withdrawal does not notify when identity does not match`() {
        val listener = RecordingListener()
        SharedPlaybackEngine.addEngineListener(listener)

        val engine = fakePlayer()
        SharedPlaybackEngine.publish(engine)
        SharedPlaybackEngine.withdraw(fakePlayer()) // wrong identity

        assertEquals(0, listener.withdrawnCount)
        assertNotNull(SharedPlaybackEngine.player)

        SharedPlaybackEngine.withdraw(engine) // rightful owner
        assertEquals(1, listener.withdrawnCount)
    }

    @Test
    fun `publication generation is monotonic`() {
        val before = SharedPlaybackEngine.generation
        SharedPlaybackEngine.publish(fakePlayer())
        SharedPlaybackEngine.publish(fakePlayer())
        val after = SharedPlaybackEngine.generation
        assertTrue("generation must advance by exactly 2", after - before == 2L)
    }

    @Test
    fun `removed listeners no longer receive lifecycle events`() {
        val listener = RecordingListener()
        SharedPlaybackEngine.addEngineListener(listener)
        SharedPlaybackEngine.removeEngineListener(listener)

        val engine = fakePlayer()
        SharedPlaybackEngine.publish(engine)
        SharedPlaybackEngine.withdraw(engine)

        assertEquals("no publish events after removal", 0, listener.publishedCount)
        assertEquals("no withdraw events after removal", 0, listener.withdrawnCount)
    }

    @Test
    fun `listeners survive withdrawal and fire on the next publication`() {
        val listener = RecordingListener()
        SharedPlaybackEngine.addEngineListener(listener)

        val first = fakePlayer()
        SharedPlaybackEngine.publish(first)
        SharedPlaybackEngine.withdraw(first)
        assertEquals(1, listener.publishedCount)

        // Service restarts and publishes a fresh engine generation:
        val second = fakePlayer()
        SharedPlaybackEngine.publish(second)

        assertEquals("latch must fire across generations", 2, listener.publishedCount)
        assertEquals(second, listener.lastPublished)
    }
}
