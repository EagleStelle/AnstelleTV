package tv.anstelle.core.player

import android.content.Context
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

/** Tiny loopback HTTP/range server: tests exercise the real OkHttp media data source. */
internal class TransitionSampleServer(context: Context) : AutoCloseable {
    private val samples = listOf("plain.mp4", "styled.mkv", "fonts.mkv").associateWith {
        context.assets.open("transitions/$it").use { input -> input.readBytes() }
    }
    private val workers = Executors.newCachedThreadPool()
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val baseUrl = "http://127.0.0.1:${socket.localPort}"

    init {
        workers.execute {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: java.io.IOException) { break }
                workers.execute {
                    client.use {
                        val input = it.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val request = input.readLine() ?: return@execute
                        val file = request.substringAfter(' ').substringBefore(' ').removePrefix("/")
                        var range: String? = null
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                        }
                        val data = samples[file] ?: return@execute
                        val bounds = range?.removePrefix("bytes=")?.split('-')
                        val start = bounds?.firstOrNull()?.toIntOrNull() ?: 0
                        val end = (bounds?.getOrNull(1)?.toIntOrNull() ?: data.lastIndex).coerceAtMost(data.lastIndex)
                        val output = it.getOutputStream()
                        val status = if (range == null) "200 OK" else "206 Partial Content"
                        val headers = buildString {
                            append("HTTP/1.1 $status\r\nContent-Length: ${end - start + 1}\r\n")
                            append("Accept-Ranges: bytes\r\nConnection: close\r\n")
                            if (range != null) append("Content-Range: bytes $start-$end/${data.size}\r\n")
                            append("\r\n")
                        }
                        try {
                            output.write(headers.toByteArray(Charsets.US_ASCII))
                            output.write(data, start, end - start + 1)
                            output.flush()
                        } catch (_: java.io.IOException) { /* A seek may cancel the old read. */ }
                    }
                }
            }
        }
    }

    override fun close() {
        socket.close()
        workers.shutdownNow()
    }
}
