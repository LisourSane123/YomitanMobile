package com.yomitanmobile.data.download

import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.domain.repository.DictionaryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The install queue: what the user sees when they tap five dictionaries in a
 * row and then walk away from the screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DownloadQueueTest {

    /**
     * Never touched: the queue test replaces the install step entirely, so
     * nothing here reaches the database or the network.
     */
    private val repository: DictionaryRepository =
        java.lang.reflect.Proxy.newProxyInstance(
            DictionaryRepository::class.java.classLoader,
            arrayOf(DictionaryRepository::class.java)
        ) { _, _, _ -> throw UnsupportedOperationException("not used") } as DictionaryRepository

    private fun manager(
        scope: CoroutineScope,
        install: suspend (DictionaryDownloadInfo) -> DownloadResult
    ) = DictionaryDownloadManager(
        ApplicationProvider.getApplicationContext(),
        repository,
        scope
    ).also { it.installer = install }

    private fun info(id: String) = DictionaryDownloadInfo(
        id = id,
        name = "Dictionary $id",
        descriptionPl = "",
        descriptionEn = "",
        category = DictionaryCategory.entries.first(),
        url = "https://github.com/x/$id.zip",
        fileSize = "1 MB"
    )

    private suspend fun awaitQueue(
        manager: DictionaryDownloadManager,
        predicate: (List<QueuedDownload>) -> Boolean
    ) = withTimeout(5_000) {
        while (!predicate(manager.queue.value)) delay(5)
        manager.queue.value
    }

    @Test
    fun `everything queued is installed, in the order it was asked for`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val installed = mutableListOf<String>()
        val manager = manager(scope) { info ->
            installed += info.id
            DownloadResult.Success(info.name, 10)
        }

        manager.enqueue(listOf(info("a"), info("b"), info("c")))
        awaitQueue(manager) { q -> q.size == 3 && q.all { it.state == QueueState.DONE } }

        assertEquals(listOf("a", "b", "c"), installed)
    }

    /**
     * The background service stops itself the moment it sees an idle queue,
     * so it has to be started AFTER the work is in the queue — and a request
     * that adds nothing must not start it at all.
     */
    @Test
    fun `the background service is started once the work is already queued`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gate = CompletableDeferred<Unit>()
        val queueAtStart = mutableListOf<List<QueuedDownload>>()
        lateinit var manager: DictionaryDownloadManager
        manager = DictionaryDownloadManager(
            ApplicationProvider.getApplicationContext(),
            repository,
            scope,
            com.yomitanmobile.data.repository.BackgroundWorkStarter { queueAtStart += manager.queue.value }
        ).also { it.installer = { info -> gate.await(); DownloadResult.Success(info.name, 1) } }

        manager.enqueue(info("a"))
        awaitQueue(manager) { q -> q.size == 1 }
        manager.enqueue(info("a"))
        delay(50)

        assertEquals(1, queueAtStart.size)
        assertEquals(listOf("a"), queueAtStart.single().map { it.info.id })
        gate.complete(Unit)
        awaitQueue(manager) { q -> q.all { it.state == QueueState.DONE } }
        Unit
    }

    @Test
    fun `a dictionary already queued is not queued twice`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gate = CompletableDeferred<Unit>()
        val manager = manager(scope) { info ->
            gate.await()
            DownloadResult.Success(info.name, 1)
        }

        manager.enqueue(info("a"))
        manager.enqueue(info("b"))
        awaitQueue(manager) { q -> q.size == 2 }
        // 'a' is installing and 'b' is waiting; asking for both again changes
        // nothing.
        manager.enqueue(listOf(info("a"), info("b")))
        delay(50)
        assertEquals(2, manager.queue.value.size)

        gate.complete(Unit)
        awaitQueue(manager) { q -> q.all { it.state == QueueState.DONE } }
        Unit
    }

    @Test
    fun `a failure does not stop the rest of the queue`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = manager(scope) { info ->
            if (info.id == "a") DownloadResult.Error(info.name, "boom")
            else DownloadResult.Success(info.name, 5)
        }

        manager.enqueue(listOf(info("a"), info("b")))
        val queue = awaitQueue(manager) { q ->
            q.size == 2 && q.none { it.state == QueueState.WAITING || it.state == QueueState.RUNNING }
        }

        assertEquals(QueueState.FAILED, queue.first { it.info.id == "a" }.state)
        assertEquals("boom", queue.first { it.info.id == "a" }.error)
        assertEquals(QueueState.DONE, queue.first { it.info.id == "b" }.state)
    }

    @Test
    fun `a waiting dictionary can be dropped, and finished ones cleared`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gate = CompletableDeferred<Unit>()
        val installed = mutableListOf<String>()
        val manager = manager(scope) { info ->
            installed += info.id
            gate.await()
            DownloadResult.Success(info.name, 1)
        }

        manager.enqueue(listOf(info("a"), info("b")))
        awaitQueue(manager) { q -> q.size == 2 }
        // Cancelling and clearing go through the queue's own lock, so they
        // land a moment after the call rather than inside it.
        manager.cancelQueued("b")
        awaitQueue(manager) { q -> q.any { it.state == QueueState.CANCELLED } }
        gate.complete(Unit)
        awaitQueue(manager) { q -> q.none { it.state == QueueState.RUNNING } }

        // The cancelled one was never installed…
        assertEquals(listOf("a"), installed)
        // …and clearing leaves nothing behind, since neither is pending.
        manager.clearFinished()
        awaitQueue(manager) { q -> q.isEmpty() }
        assertTrue(manager.queue.value.isEmpty())
    }

    @Test
    fun `a queue that has drained can always be filled again`() = runBlocking {
        // The bug this pins: the worker decided "nothing left, I am done" and
        // a tap arriving in the moment before that coroutine actually finished
        // saw a worker that was still "active" and started no new one. The
        // dictionary then sat at "w kolejce" forever. Short installs — a 1-2 MB
        // frequency list — hit that window often, which is why queueing four
        // frequency lists one tap at a time was the way to see it.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val installed = mutableListOf<String>()
        val manager = manager(scope) { info ->
            synchronized(installed) { installed += info.id }
            DownloadResult.Success(info.name, 1)
        }

        repeat(30) { i ->
            manager.enqueue(info("d$i"))
            awaitQueue(manager) { q -> q.any { it.info.id == "d$i" && it.state == QueueState.DONE } }
        }

        assertEquals((0 until 30).map { "d$it" }, synchronized(installed) { installed.toList() })
    }

    @Test
    fun `more can be queued while one is installing`() = runBlocking {
        // The screen used to disable every download button for as long as any
        // install was running, so the queue could only ever be filled before
        // the first one started. The manager side of that promise: an item
        // added mid-install is picked up as soon as the current one ends.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gate = CompletableDeferred<Unit>()
        val installed = mutableListOf<String>()
        val manager = manager(scope) { info ->
            synchronized(installed) { installed += info.id }
            if (info.id == "a") gate.await()
            DownloadResult.Success(info.name, 1)
        }

        manager.enqueue(info("a"))
        awaitQueue(manager) { q -> q.any { it.info.id == "a" && it.state == QueueState.RUNNING } }

        manager.enqueue(info("b"))
        manager.enqueue(info("c"))
        awaitQueue(manager) { q -> q.count { it.state == QueueState.WAITING } == 2 }

        gate.complete(Unit)
        awaitQueue(manager) { q ->
            q.size == 3 && q.all { it.state == QueueState.DONE }
        }
        assertEquals(listOf("a", "b", "c"), synchronized(installed) { installed.toList() })
    }
}
