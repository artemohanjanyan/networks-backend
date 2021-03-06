package ru.ifmo.networks.master

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.BodyExtractors
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.ServerResponse.*
import reactor.core.publisher.Mono
import ru.ifmo.networks.common.*
import ru.ifmo.networks.common.handlers.HandlerWorker
import ru.ifmo.networks.common.storage.DiskStorage
import ru.ifmo.networks.common.storage.LruStorage
import ru.ifmo.networks.common.storage.Storage
import java.time.Duration

class MasterHandlerWorker : HandlerWorker {

    companion object {
        private val FRAGMENT_DURATION = Duration.ofSeconds(2)
    }

    private val map = SelfClearingMap()

    private val storage: Storage

    init {
        var tmp: Storage? = null
        val fallback = object: Storage {
            override fun getFragment(streamInfo: StreamInfo): ByteArray? {
                return tmp!!.getFragment(streamInfo)
            }
        }
        storage = LruStorage(60,
                DiskStorage(
                        MalinkaStorage({
                            map.getStream(it)?.baseUrl
                        }, { duration, streamInfo ->
                            if (duration > FRAGMENT_DURATION) {
                                reportSlowSpeed(duration, streamInfo)
                            }
                        }, fallback)
                )
        )
        tmp = storage
    }

    override fun malinkaHeartbeat(serverRequest: ServerRequest): Mono<ServerResponse> {
        println("malinkaHeartbeat $serverRequest")
        return serverRequest
                .body { httpRequest, context ->
                    val ip = httpRequest.remoteAddress?.address?.hostAddress
                            ?: return@body Mono.empty<WithIP<HeartbeatRequest>>()
                    val request = BodyExtractors
                            .toMono(HeartbeatRequest::class.java)
                            .extract(httpRequest, context)
                    request.map { WithIP(ip, it) }
                }
                .map { requestWithIp ->
                    val request = requestWithIp.data
                    val url = "http://${requestWithIp.ip}"
                    map.update(request.name, SelfClearingMap.StreamBaseUrlAndFragment(url, request.fragment))
                }
                .flatMap {
                    ok().withDefaultHeader().jsonSuccess("Ok")
                }
                .switchIfEmpty(
                        badRequest().jsonFail(ErrorResponse("Address unresolved", "Address unresolved"))
                )
    }

    override fun getStreams(serverRequest: ServerRequest): Mono<ServerResponse> {
        println("getStreams $serverRequest")
        return ok()
                .withDefaultHeader()
                .jsonSuccess(StreamsResponse(
                        map.asList()
                                .map { pair -> StreamInfo(pair.first, pair.second.fragment) }
                ))
    }

    override fun getFragment(serverRequest: ServerRequest): Mono<ServerResponse> {
        println("getFragment $serverRequest")
        val name = serverRequest.pathVariable("name") ?: return badRequest().build()
        val fragment = serverRequest.pathVariable("fragment") ?: return badRequest().build()

        return if (fragment.endsWith(".m3u8")) {
            getM3U8Fragment(name, fragment)
        } else {
            getTSFragment(name, fragment)
        }
    }

    private fun getM3U8Fragment(name: String, fragment: String): Mono<ServerResponse> {
        assert(fragment.endsWith(".m3u8"))

        return withStreamCheck(
                name = name,
                executor = { url ->
                    val response = MalinkaProxy(url).download(fragment)
                    ok().withDefaultHeader()
                            .contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"))
                            .writeByteContent(response)
                }
        )
    }

    private fun getTSFragment(name: String, fragment: String): Mono<ServerResponse> {
        assert(fragment.endsWith(".ts"))
        return withStreamCheck(
                name = name,
                executor = {
                    val response = storage.getFragment(StreamInfo(name, fragment))!!
                    ok().withDefaultHeader()
                            .contentType(MediaType.parseMediaType("video/mp2t"))
                            .writeByteContent(response)
                }
        )
    }

    private fun withStreamCheck(
            name: String,
            executor: (String) -> Mono<ServerResponse>): Mono<ServerResponse> {
        val url = map.getStream(name)?.baseUrl ?:
                return status(HttpStatus.NOT_FOUND)
                        .withDefaultHeader()
                        .jsonFail(ErrorResponse("Not Found", "No stream with such name!"))

        return executor(url)
    }

    private fun reportSlowSpeed(duration: Duration, streamInfo: StreamInfo) {
        println("slow speed, duration $duration")

//        val restTemplate = RestTemplate()
//        val status = restTemplate.exchange(
//                "${map.getStream(streamInfo.name)?.baseUrl}/bandwidth",
//                HttpMethod.POST,
//                null,
//                String::class.java)
//        if (status.statusCode != HttpStatus.OK) {
//            println("лолчто")
//        }
//        map.remove(streamInfo.name)
    }

}