package org.bittrace.data

import java.awt.EventQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the hand-off from the sidecar reader thread to the event thread.
 *
 * Mutations are queued and applied in batches, which buys back the per-message
 * runnable the live path used to post — but batching is only correct while the
 * order of the queue is the order the messages arrived in, and while a UI
 * action taken on the event thread cannot slip in front of events handed over
 * before it. Those two are what these tests are for; the rest of the store is
 * plain enough to read.
 */
class SessionStoreTest {

    // --- helpers -----------------------------------------------------------

    private fun request(id: String, url: String = "https://example.test/$id") = InitialRequestData(
        id = id,
        startedDateTime = "2026-09-17T10:00:00.000Z",
        request = InitialRequestData.RequestHead(
            method = "GET",
            url = url,
            httpVersion = "HTTP/1.1",
            headersSize = 0,
            bodySize = 0,
        ),
        tls = "TLSv1.3",
    )

    private fun response(id: String, status: Int = 200) = InitialResponseData(
        id = id,
        serverIPAddress = "127.0.0.1",
        connection = "1",
        error = false,
        response = InitialResponseData.ResponseHead(
            status = status,
            statusText = "OK",
            httpVersion = "HTTP/1.1",
            headersSize = 0,
            bodySize = 0,
            redirectURL = "",
        ),
        timings = HarTimings(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        time = 1.0,
    )


    private fun completeResponse(id: String, upgrade: String) = CompleteResponseMessage(
        id = id,
        response = CompleteResponseMessage.ResponseBody(
            headers = listOf(NameValuePair("Connection", "Upgrade"), NameValuePair("Upgrade", upgrade)),
            bodySize = 0,
            // A 1xx response cannot carry a body at all, whatever its headers
            // claim — everything after the header block belongs to whichever
            // protocol was switched to.
            content = HarContent(size = 0, mimeType = "", wireSize = 0),
        ),
        timings = CompleteResponseMessage.Timings(receive = 0.0),
        time = 1.0,
    )

    /**
     * Runs [feed] on a non-event thread — the reader thread's position — and
     * returns once every mutation it handed over has been applied.
     *
     * The second `invokeAndWait` is the one that matters: the first flushes the
     * drain that was scheduled, and a drain that found more waiting re-posts
     * itself rather than looping, so one round trip is not always enough.
     */
    private fun feedAndSettle(feed: () -> Unit) {
        val done = CountDownLatch(1)
        Thread { feed(); done.countDown() }.start()
        assertTrue(done.await(10, TimeUnit.SECONDS), "feeder thread did not finish")
        repeat(4) { EventQueue.invokeAndWait { } }
    }

    private fun onUi(block: () -> Unit) = EventQueue.invokeAndWait(block)

    // --- tests -------------------------------------------------------------

    @Test
    fun `batched arrivals all land, in arrival order`() {
        val store = SessionStore()
        feedAndSettle {
            repeat(500) { store.onInitialRequest(request("f$it")) }
        }

        onUi {
            assertEquals(500, store.size)
            // Arrival order, and one row per flow rather than a merge gone wrong.
            store.rows.forEachIndexed { index, row ->
                assertEquals("f$index", row.id)
                assertEquals(index + 1, row.rowCount)
            }
        }
    }

    @Test
    fun `merges by id across separate messages in one batch`() {
        val store = SessionStore()
        feedAndSettle {
            store.onInitialRequest(request("a"))
            store.onInitialResponse(response("a", status = 418))
            store.onInitialRequest(request("b"))
        }

        onUi {
            assertEquals(2, store.size)
            val a = assertNotNull(store.get("a"))
            assertEquals(418, a.response?.response?.status, "response merged onto its request")
            assertNull(assertNotNull(store.get("b")).response)
        }
    }

    @Test
    fun `a duplicate id is ignored rather than added twice`() {
        val store = SessionStore()
        feedAndSettle {
            store.onInitialRequest(request("dup"))
            store.onInitialRequest(request("dup", url = "https://example.test/second"))
        }

        onUi {
            assertEquals(1, store.size)
            assertEquals("https://example.test/dup", store.get("dup")?.request?.request?.url)
        }
    }

    @Test
    fun `clear inside an event does not let rows queued behind it outlive it`() {
        val store = SessionStore()

        // The whole test happens inside one event, so the drain this feeding
        // schedules cannot run until we return — which is exactly the window a
        // UI action lands in. `clear()` has to take the queue with it; posting
        // the rows and clearing around them would let every one of them land
        // behind the clear and repopulate a list the user just emptied.
        EventQueue.invokeAndWait {
            val handedOver = CountDownLatch(1)
            Thread {
                repeat(200) { store.onInitialRequest(request("f$it")) }
                handedOver.countDown()
            }.start()
            assertTrue(handedOver.await(10, TimeUnit.SECONDS), "feeder thread did not finish")

            store.clear()
        }
        repeat(4) { EventQueue.invokeAndWait { } }

        onUi {
            assertEquals(0, store.size, "rows handed over before clear() survived it")
            assertNull(store.get("f0"))
        }
    }

    @Test
    fun `capacity evicts oldest rows and forgets them by id`() {
        val store = SessionStore(capacity = 50)
        feedAndSettle {
            repeat(200) { store.onInitialRequest(request("f$it")) }
        }

        onUi {
            assertEquals(50, store.size)
            assertEquals("f150", store.rows.first().id, "oldest rows evicted first")
            assertEquals("f199", store.rows.last().id)
            assertNull(store.get("f149"), "an evicted row is dropped from the id index too")
            assertNotNull(store.get("f150"))
        }
    }

    @Test
    fun `an unbounded store keeps everything`() {
        val store = SessionStore()
        feedAndSettle { repeat(300) { store.onInitialRequest(request("f$it")) } }
        onUi { assertEquals(300, store.size) }
    }

    @Test
    fun `every live flow is reported once, in order`() {
        val seen = mutableListOf<String>()
        val store = SessionStore(onLiveFlow = { seen += it })

        feedAndSettle { repeat(100) { store.onInitialRequest(request("f$it")) } }

        onUi {
            assertEquals(100, seen.size, "one report per live flow, no duplicates from re-drains")
        }
    }

    @Test
    fun `concurrent feeders all land exactly once`() {
        val store = SessionStore()
        val threads = 4
        val each = 250
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            Thread {
                repeat(each) { store.onInitialRequest(request("t$t-f$it")) }
                done.countDown()
            }.start()
        }
        assertTrue(done.await(20, TimeUnit.SECONDS), "feeder threads did not finish")
        repeat(8) { EventQueue.invokeAndWait { } }

        onUi {
            assertEquals(threads * each, store.size)
            assertEquals(threads * each, store.rows.map { it.id }.toSet().size, "no row applied twice")
            // Each feeder's own rows stay in the order that feeder sent them.
            repeat(threads) { t ->
                val mine = store.rows.map { it.id }.filter { it.startsWith("t$t-") }
                assertEquals((0 until each).map { "t$t-f$it" }, mine)
            }
        }
    }

    // --- websockets --------------------------------------------------------

    private fun wsMessage(id: String, seq: Long, fromClient: Boolean = true) = WebSocketRecord(
        message = WebSocketMessageData(
            id = id,
            seq = seq,
            fromClient = fromClient,
            type = "text",
            size = 8,
            timestamp = "2026-09-17T10:00:0${seq % 10}.000Z",
        ),
        payload = ByteArray(8) { seq.toByte() },
    )

    @Test
    fun `messages land on the handshake's row and mark it a websocket`() {
        val store = SessionStore()
        feedAndSettle {
            store.onInitialRequest(request("ws"))
            store.onInitialResponse(response("ws", status = 101))
            repeat(3) { store.onWebSocketMessage("ws", wsMessage("ws", it.toLong(), fromClient = it % 2 == 0)) }
            store.onWebSocketEnd(WebSocketEndData(id = "ws", closeCode = 1000, messages = 3))
        }

        onUi {
            val row = assertNotNull(store.get("ws"))
            assertTrue(row.isWebSocket)
            assertEquals(3, row.webSocketMessages.size)
            // Arrival order, which is the order of the conversation.
            assertEquals(listOf(0L, 1L, 2L), row.webSocketMessages.map { it.message.seq })
            assertEquals(1000, row.webSocketEnd?.closeCode)
            assertEquals(0, row.webSocketEvicted, "nothing was dropped at three messages")
        }
    }

    /**
     * A socket with no end to wait for cannot be allowed to grow a row without
     * bound. Past the cap the oldest go, and the row says how many — a
     * transcript that silently became a tail would read as the whole thing.
     */
    @Test
    fun `an endless socket keeps the newest messages and counts what it dropped`() {
        val store = SessionStore()
        val sent = 5_200L
        feedAndSettle {
            store.onInitialRequest(request("ws"))
            for (seq in 0 until sent) store.onWebSocketMessage("ws", wsMessage("ws", seq))
        }

        onUi {
            val row = assertNotNull(store.get("ws"))
            assertEquals(5_000, row.webSocketMessages.size)
            assertEquals(sent - 5_000, row.webSocketEvicted)
            // The newest are the ones kept: the tail, not the head.
            assertEquals(sent - 1, row.webSocketMessages.last().message.seq)
            assertEquals(sent - 5_000, row.webSocketMessages.first().message.seq)
            assertEquals(5_000L * 8, row.webSocketBytes)
        }
    }

    /** A message for a flow the store never saw is dropped, not stored loose. */
    @Test
    fun `a message for an unknown flow is ignored`() {
        val store = SessionStore()
        feedAndSettle { store.onWebSocketMessage("gone", wsMessage("gone", 0)) }
        onUi { assertEquals(0, store.size) }
    }

    /**
     * A `101` says the connection switched protocols, not to what. Only the
     * `Upgrade` header names it, and a flow that switched to something else
     * will never produce a message — so it must not be taken for a socket, or
     * it gets a transcript tab that stays empty for good.
     */
    @Test
    fun `a 101 is a websocket only when the upgrade header says so`() {
        val store = SessionStore()
        feedAndSettle {
            store.onInitialRequest(request("ws"))
            store.onInitialResponse(response("ws", status = 101))
            store.onCompleteResponse(completeResponse("ws", upgrade = "websocket"))

            store.onInitialRequest(request("h2c"))
            store.onInitialResponse(response("h2c", status = 101))
            store.onCompleteResponse(completeResponse("h2c", upgrade = "h2c"))
        }

        onUi {
            assertTrue(assertNotNull(store.get("ws")).isWebSocket)
            assertFalse(assertNotNull(store.get("h2c")).isWebSocket, "an h2c upgrade is not a socket")
        }
    }

    /** Frames that arrived settle it regardless of what the headers said. */
    @Test
    fun `messages make a flow a websocket even without the handshake headers`() {
        val store = SessionStore()
        feedAndSettle {
            store.onInitialRequest(request("ws"))
            store.onWebSocketMessage("ws", wsMessage("ws", 0))
        }
        onUi { assertTrue(assertNotNull(store.get("ws")).isWebSocket) }
    }

    @Test
    fun `tls lands on every row of its connection, whichever arrives first`() {
        val store = SessionStore()
        feedAndSettle {
            // The CONNECT row is first; the handshakes follow it; the request
            // inside the tunnel comes last and must still find them.
            store.onInitialRequest(request("connect").copy(clientConnectionId = "c1", tls = ""))
            store.onTlsHandshake(
                TlsHandshakeData(clientConnectionId = "c1", side = "server", established = true, version = "TLSv1.3"),
            )
            store.onTlsHandshake(
                TlsHandshakeData(clientConnectionId = "c1", side = "client", established = false, error = "unknown ca"),
            )
            store.onInitialRequest(request("inner").copy(clientConnectionId = "c1"))
            store.onInitialRequest(request("other").copy(clientConnectionId = "c2"))
        }

        onUi {
            val connect = assertNotNull(store.get("connect")?.tls)
            assertTrue(connect === store.get("inner")?.tls)
            assertEquals("TLSv1.3", connect.serverHandshake?.version)
            assertEquals("unknown ca", connect.failure?.error)
            assertTrue(assertNotNull(store.get("other")?.tls).isEmpty)

            // The handshake got a row of its own, one per connection, sharing
            // the same state — and a failed one reads as failed.
            val tlsRows = store.rows.filter { it.isTls }
            assertEquals(1, tlsRows.size)
            assertTrue(tlsRows.single().tls === connect)
            assertEquals(TLS_METHOD, tlsRows.single().request.request.method)
            assertEquals(true, tlsRows.single().failed)
        }
    }

    @Test
    fun `a client hello opens a tls row named for its sni`() {
        val store = SessionStore()
        feedAndSettle {
            store.onTlsClientHello(
                TlsClientHelloData(clientConnectionId = "c9", sni = "example.com", destination = "93.184.216.34:443"),
            )
        }
        onUi {
            val row = store.rows.single()
            assertTrue(row.isTls)
            assertEquals("example.com:443", row.request.request.url)
            // No handshake yet: neither failed nor succeeded.
            assertNull(row.failed)
        }
    }
}
