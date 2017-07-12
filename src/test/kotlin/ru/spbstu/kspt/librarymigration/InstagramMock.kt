package ru.spbstu.kspt.librarymigration

import org.eclipse.jetty.http.HttpURI
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.AbstractHandler
import java.io.File
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory

/**
 * Created by artyom on 27.04.17.
 */

class InstagramMock : AbstractHandler() {
    val file = File("examples/instagram-log-full.txt")
    val requests = mutableListOf<LoggedRequest>()

    var logger = LoggerFactory.getLogger(InstagramMock::class.java)

    fun read() {
        var req = LoggedRequest()
        var state = ReaderState.START
        val responseCodeRegex = Regex("^<-- (\\d{3}) .*")
        file.forEachLine { line ->
            when {
                line.startsWith("--> GET") || line.startsWith("--> POST") -> {
                    val splitted = line.removePrefix("--> ").split(' ')
                    req.method = splitted.get(0)
                    req.url = HttpURI(splitted.get(1))
                    state = ReaderState.REQ_HEADERS
                }
                line.startsWith("--> END GET") || line.startsWith("--> END POST") -> {
                    state = ReaderState.START
                }
                line.matches(responseCodeRegex) -> {
                    req.code = checkNotNull(responseCodeRegex.find(line)).groupValues.get(1).toInt()
                    state = ReaderState.RESP_HEADERS
                }
                line.startsWith("<-- END HTTP") -> {
                    state = ReaderState.START
                    requests += req
                    req = LoggedRequest()
                }
                state == ReaderState.RESP_HEADERS && line.isEmpty() -> {
                    state = ReaderState.RESP_CONTENT
                }
                state == ReaderState.RESP_HEADERS -> {
                    req.respHeaders += line.split(": ").toPair()
                }
                state == ReaderState.RESP_CONTENT -> {
                    req.response += line
                }
                state == ReaderState.REQ_HEADERS && line.isEmpty() -> {
                    state = ReaderState.REQ_CONTENT
                }
                state == ReaderState.REQ_HEADERS -> {
                    req.reqHeaders += line.split(": ").toPair()
                }
                state == ReaderState.REQ_CONTENT -> {
                    req.requestContent += line
                }
            }
        }
        println("Done")
    }

    override fun handle(target: String, baseRequest: Request, request: HttpServletRequest, response: HttpServletResponse) {
        println("Got request: $target")
        val loggedReq = requests.first { it.method == request.method && it.url.pathQuery == baseRequest.httpURI.pathQuery }
        for ((key, value) in loggedReq.reqHeaders) {
            if ((key + value).contains("csrf", ignoreCase = true)) {
                continue
            }
            if (request.getHeader(key) != value) {
                println("Expected $key=$value, got ${request.getHeader(key)}, content ${baseRequest.reader.readLine()}")
                error("Incorrect header")
            }
        }
        for ((key, value) in loggedReq.respHeaders) {
            response.setHeader(key, value)
        }
        response.status = loggedReq.code
        response.writer.write(loggedReq.response)
        baseRequest.isHandled = true;
    }

    class LoggedRequest {
        var url: HttpURI = HttpURI()
        var reqHeaders: Map<String, String> = mutableMapOf()
        var respHeaders: Map<String, String> = mutableMapOf()
        var code: Int = 0
        var response: String = ""
        var requestContent: String = ""
        var method: String = ""
    }

    enum class ReaderState {
        REQ_HEADERS, RESP_HEADERS, START, REQ_CONTENT, RESP_CONTENT
    }

    fun <T> List<T>.toPair(): Pair<T, T> = Pair(get(0), get(1))
}