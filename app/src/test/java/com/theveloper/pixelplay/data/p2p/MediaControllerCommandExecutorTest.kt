package com.theveloper.pixelplay.data.p2p

import com.theveloper.pixelplay.presentation.viewmodel.PlaybackStateHolder
import com.theveloper.pixelplay.shared.p2p.P2pCommandAction
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import androidx.media3.session.MediaController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Executor dispatch: action -> MediaController calls, thread-dispatched, null-controller safe. */
class MediaControllerCommandExecutorTest {

    private fun executorWith(
        controller: MediaController?,
        dispatched: MutableList<Runnable> = mutableListOf(),
    ): Pair<MediaControllerCommandExecutor, MutableList<Runnable>> {
        val playbackStateHolder = mockk<PlaybackStateHolder>(relaxed = true)
        every { playbackStateHolder.mediaController } returns controller
        val executor = MediaControllerCommandExecutor(playbackStateHolder) { it.run() }
        return executor to dispatched
    }

    private fun controller(): MediaController = mockk(relaxed = true) {
        every { isPlaying } returns true
    }

    @Test
    fun `play and pause map one to one`() {
        val controller = controller()
        val (executor, _) = executorWith(controller)
        assertTrue(executor.execute(P2pCommandAction.PLAY, null))
        verify { controller.play() }
        assertTrue(executor.execute(P2pCommandAction.PAUSE, null))
        verify { controller.pause() }
    }

    @Test
    fun `toggle pauses when playing and plays when not`() {
        val playing = controller() // isPlaying = true
        val (executor1, _) = executorWith(playing)
        assertTrue(executor1.execute(P2pCommandAction.TOGGLE, null))
        verify { playing.pause() }

        val stopped = mockk<MediaController>(relaxed = true) { every { isPlaying } returns false }
        val (executor2, _) = executorWith(stopped)
        assertTrue(executor2.execute(P2pCommandAction.TOGGLE, null))
        verify { stopped.play() }
    }

    @Test
    fun `stop maps to pause plus seek to zero (documented reversible mapping)`() {
        val controller = controller()
        val (executor, _) = executorWith(controller)
        assertTrue(executor.execute(P2pCommandAction.STOP, null))
        verify {
            controller.pause()
            controller.seekTo(0L)
        }
    }

    @Test
    fun `seek converts seconds to milliseconds and requires positionSec`() {
        val controller = controller()
        val (executor, _) = executorWith(controller)
        assertTrue(executor.execute(P2pCommandAction.SEEK, 42.5))
        verify { controller.seekTo(42_500L) }

        // Contract: positionSec is REQUIRED for seek. A missing value cannot be applied
        // and must dispatch nothing — exactly one seekTo happened in total.
        assertFalse(executor.execute(P2pCommandAction.SEEK, null))
        verify(exactly = 1) { controller.seekTo(any<Long>()) }
    }

    @Test
    fun `next and previous map to the controller queue moves`() {
        val controller = controller()
        val (executor, _) = executorWith(controller)
        assertTrue(executor.execute(P2pCommandAction.NEXT, null))
        assertTrue(executor.execute(P2pCommandAction.PREVIOUS, null))
        verify {
            controller.seekToNext()
            controller.seekToPrevious()
        }
    }

    @Test
    fun `no local player session returns false (unsupported)`() {
        val (executor, _) = executorWith(controller = null)
        assertFalse(executor.execute(P2pCommandAction.PLAY, null))
    }

    @Test
    fun `player calls are dispatched off the caller thread via the injected dispatcher`() {
        val controller = controller()
        val dispatched = mutableListOf<Runnable>()
        val playbackStateHolder = mockk<PlaybackStateHolder>(relaxed = true)
        every { playbackStateHolder.mediaController } returns controller
        val executor = MediaControllerCommandExecutor(playbackStateHolder) { dispatched += it }
        assertTrue(executor.execute(P2pCommandAction.PAUSE, null))
        assertEquals(1, dispatched.size, "commands must be dispatched (main looper in prod)")
        verify(exactly = 0) { controller.pause() }
        dispatched.single().run()
        verify(exactly = 1) { controller.pause() }
    }
}
