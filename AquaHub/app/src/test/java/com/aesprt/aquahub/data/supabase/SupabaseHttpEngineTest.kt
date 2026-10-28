package com.aesprt.aquahub.data.supabase

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readAvailable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseHttpEngineTest {
    @Test
    fun `cancelling a partially received response closes it and client remains usable`() = runBlocking {
        // This uses the real production engine and local sockets, not a mock
        // HTTP engine or the production backend. Android StrictMode still
        // requires an on-device regression check.
        val listening = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val server = Executors.newSingleThreadExecutor()
        val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val responseStarted = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<Boolean>()
        val client = HttpClient(createSupabaseHttpEngine())
        val serving = server.submit {
            repeat(2) { index ->
                listening.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { /* request headers */ }
                    val output = socket.getOutputStream()
                    if (index == 0) {
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\nx".toByteArray())
                        output.flush()
                        val closed = try { input.read() == -1 } catch (_: SocketException) { true }
                        disconnected.complete(closed)
                    } else {
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                        output.flush()
                    }
                }
            }
        }
        try {
            withTimeout(10_000) {
                val request = launch(ui) {
                    client.prepareGet("http://127.0.0.1:${listening.localPort}/slow").execute { response ->
                        assertEquals(1, response.bodyAsChannel().readAvailable(ByteArray(1)))
                        responseStarted.complete(Unit)
                        awaitCancellation()
                    }
                }
                responseStarted.await()
                request.cancelAndJoin()
                assertTrue(request.isCancelled)
                assertTrue(disconnected.await())
                assertEquals("ok", client.get("http://127.0.0.1:${listening.localPort}/retry").bodyAsText())
            }
        } finally {
            client.close()
            listening.close()
            serving.cancel(true)
            server.shutdownNow()
            ui.close()
        }
    }
}
