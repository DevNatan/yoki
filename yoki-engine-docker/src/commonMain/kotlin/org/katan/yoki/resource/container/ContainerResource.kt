package org.katan.yoki.resource.container

import io.ktor.client.call.receive
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.HttpStatement
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.ByteOrder
import io.ktor.utils.io.core.ExperimentalIoApi
import io.ktor.utils.io.core.readInt
import io.ktor.utils.io.readPacket
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.katan.yoki.ContainerAlreadyStartedException
import org.katan.yoki.ContainerException.Companion.CONTAINER_ID_PROPERTY
import org.katan.yoki.ContainerNotFoundException
import org.katan.yoki.ContainerRemoveConflictException
import org.katan.yoki.DockerEngine
import org.katan.yoki.model.Frame
import org.katan.yoki.model.Stream
import org.katan.yoki.model.container.Container
import org.katan.yoki.util.requestCatching
import org.katan.yoki.util.throwResourceException
import kotlin.time.Duration

/**
 * @see ContainerResource
 */
public class ContainerResource(private val engine: DockerEngine) {

    private companion object {
        private const val BASE_PATH = "/containers"
        private val LINE_BREAK_REGEX: Regex = Regex("\\r\\n|\\n|\\r")
    }

    /**
     * Returns a list of all containers.
     */
    public suspend fun list(): List<Container> {
        return engine.httpClient.get("$BASE_PATH/json")
    }

    /**
     * Returns a list of all containers.
     *
     * @param options Options to customize the listing result.
     */
    public suspend fun list(options: ContainerListOptions): List<Container> {
        return engine.httpClient.get("$BASE_PATH/json") {
            parameter("all", options.all)
            parameter("limit", options.limit)
            parameter("size", options.size)
            parameter("filters", engine.json.encodeToString(options.filters))
        }
    }

    /**
     * Creates a new container.
     */
    public suspend fun create(options: ContainerCreateOptions): String {
        requireNotNull(options.image) { "Container Image is required" }

        @Serializable
        class Result(
            @SerialName("Id") val id: String
        )

        // TODO print warnings
        return engine.httpClient.post<Result>("$BASE_PATH/create") {
            options.name?.let { parameter("name", it) }
            body = options
        }.id
    }

    /**
     * Starts a container.
     *
     * @param id The container id to be started.
     * @param detachKeys The key sequence for detaching a container.
     * @throws ContainerAlreadyStartedException If the container was already started.
     * @throws ContainerNotFoundException If container was not found.
     */
    public suspend fun start(id: String, detachKeys: String? = null) {
        engine.httpClient.requestCatching({
            post<Unit>("$BASE_PATH/$id/start") {
                parameter("detachKeys", detachKeys)
            }
        }, {
            val props: Map<String, Any?> = mapOf(CONTAINER_ID_PROPERTY to id, "detachKeys" to detachKeys)
            when (response.status) {
                HttpStatusCode.NotModified -> throwResourceException(::ContainerAlreadyStartedException, props)
                HttpStatusCode.NotFound -> throwResourceException(::ContainerNotFoundException, props)
            }
        })
    }

    public suspend fun stop(id: String, timeout: Int? = null) {
        engine.httpClient.post<Unit>("$BASE_PATH/$id/stop") {
            parameter("t", timeout)
        }
    }

    public suspend fun restart(id: String, timeout: Int? = null) {
        engine.httpClient.post<Unit>("$BASE_PATH/$id/restart") {
            parameter("t", timeout)
        }
    }

    public suspend fun kill(id: String, signal: String? = null) {
        engine.httpClient.post<Unit>("$BASE_PATH/$id/kill") {
            parameter("signal", signal)
        }
    }

    public suspend fun rename(id: String, newName: String) {
        engine.httpClient.post<Unit>("$BASE_PATH/$id/rename") {
            parameter("name", newName)
        }
    }

    public suspend fun pause(id: String) {
        engine.httpClient.post<Unit>("$BASE_PATH/$id/pause")
    }

    public suspend fun unpause(id: String) {
        engine.httpClient.post<Unit>("$BASE_PATH/$id/unpause")
    }

    public suspend fun remove(id: String) {
        engine.httpClient.requestCatching({
            delete<Unit>("$BASE_PATH/$id")
        }, {
            val props: Map<String, Any?> = mapOf(CONTAINER_ID_PROPERTY to id)
            when (response.status) {
                HttpStatusCode.NotFound -> throwResourceException(::ContainerNotFoundException, props)
                HttpStatusCode.Conflict -> throwResourceException(::ContainerRemoveConflictException, props)
            }
        })
    }

    public suspend fun remove(id: String, options: ContainerRemoveOptions) {
        engine.httpClient.delete<Unit>("$BASE_PATH/$id") {
            parameter("v", options.removeAnonymousVolumes)
            parameter("force", options.force)
            parameter("link", options.unlink)
        }
    }

    /**
     * Returns low-level information about a container.
     *
     * @param id ID or name of the container.
     * @param size Should return the size of container as fields `SizeRw` and `SizeRootFs`
     */
    public suspend fun inspect(id: String, size: Boolean = false): Container {
        return engine.httpClient.post("$BASE_PATH/$id/json") {
            parameter("size", size)
        }
    }

    public fun logs(id: String): Flow<Frame> {
        return logs(id) {
            follow = true
            stderr = true
            stdout = true
        }
    }

    @OptIn(ExperimentalIoApi::class)
    public fun logs(id: String, options: ContainerLogsOptions): Flow<Frame> = flow {
        engine.httpClient.get<HttpStatement>("$BASE_PATH/$id/logs") {
            parameter("follow", options.follow)
            parameter("stdout", options.stdout)
            parameter("stderr", options.stderr)
            parameter("since", options.since)
            parameter("until", options.until)
            parameter("timestamps", options.showTimestamps)
            parameter("tail", options.tail)
        }.execute { response ->
            val channel = response.content
            while (!channel.isClosedForRead) {
                val fb = channel.readByte()
                val stream = Stream.typeOfOrNull(fb)
                // println("[debug] TTY: ${stream == null}")

                // Unknown stream = tty enabled
                if (stream == null) {
                    val remaining = channel.availableForRead

                    // Remaining +1 includes the previously read first byte. Reinsert the first byte since we read it
                    // before but the type was not expected, so this byte is actually the first character of the line.
                    val len = remaining + 1
                    val payload = ByteReadChannel(
                        ByteArray(len) {
                            if (it == 0) fb else channel.readByte()
                        }
                    )

                    val line = payload.readUTF8Line() ?: error("Payload cannot be null")

                    // Try to determine the "correct" stream since we cannot have this information.
                    val stdoutEnabled = options.stdout ?: false
                    val stdErrEnabled = options.stderr ?: false
                    val expectedStream: Stream = stream ?: when {
                        stdoutEnabled && !stdErrEnabled -> Stream.StdOut
                        stdErrEnabled && !stdoutEnabled -> Stream.StdErr
                        else -> Stream.Unknown
                    }

                    emit(Frame(line, len, expectedStream))
                    continue
                }

                val header = channel.readPacket(7)

                // We discard the first three bytes because the payload size is in the last four bytes
                // and the total header size is 8.
                header.discard(3)

                val payloadLength = header.readInt(ByteOrder.BIG_ENDIAN)
                val payloadData = channel.readUTF8Line(payloadLength)!!
                emit(Frame(payloadData, payloadLength, stream))
            }
        }
    }

    public suspend fun prune(): ContainerPruneResult {
        return engine.httpClient.post("$BASE_PATH/prune")
    }

    public suspend fun prune(filters: ContainerPruneFilters): ContainerPruneResult {
        return engine.httpClient.post("$BASE_PATH/prune") {
            parameter("filters", engine.json.encodeToString(filters))
        }
    }

    public suspend fun wait(id: String, condition: String? = null): ContainerWaitResult {
        return engine.httpClient.post("$BASE_PATH/$id/wait") {
            parameter("condition", condition)
        }
    }

    public fun attach(containerIdOrName: String): Flow<Frame> = flow {
        engine.httpClient.post<HttpStatement>("$BASE_PATH/$containerIdOrName/attach") {
            parameter("stream", "true")
            parameter("stdin", "true")
            parameter("stdout", "true")
            parameter("stderr", "true")
        }.execute { response ->
            val channel = response.receive<ByteReadChannel>()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line()
                if (line == null) {
                    println("Null")
                    break
                }

                println("Remaining: ${channel.availableForRead}")

                // TODO handle stream type
                emit(Frame(line, line.length, Stream.StdOut))
            }

            println("End")
        }
    }
}

public suspend inline fun ContainerResource.restart(id: String, timeout: Duration) {
    return restart(id, timeout.inWholeSeconds.toInt())
}

public suspend inline fun ContainerResource.stop(id: String, timeout: Duration) {
    return stop(id, timeout.inWholeSeconds.toInt())
}

public suspend inline fun ContainerResource.list(block: ContainerListOptions.() -> Unit): List<Container> {
    return list(ContainerListOptions().apply(block))
}

public suspend inline fun ContainerResource.create(block: ContainerCreateOptions.() -> Unit): String {
    return create(ContainerCreateOptions().apply(block))
}

public suspend inline fun ContainerResource.remove(id: String, block: ContainerRemoveOptions.() -> Unit) {
    return remove(id, ContainerRemoveOptions().apply(block))
}

public inline fun ContainerResource.logs(id: String, block: ContainerLogsOptions.() -> Unit): Flow<Frame> {
    return logs(id, ContainerLogsOptions().apply(block))
}

public suspend inline fun ContainerResource.prune(block: ContainerPruneFilters.() -> Unit): ContainerPruneResult {
    return prune(ContainerPruneFilters().apply(block))
}
