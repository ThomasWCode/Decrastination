package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.ports.Log
import com.thomaswcode.decrastination.sync.DeviceClaim
import com.thomaswcode.decrastination.sync.Protocol
import com.thomaswcode.decrastination.sync.SeedBatch
import com.thomaswcode.decrastination.sync.protocol.CommandBatch
import com.thomaswcode.decrastination.views.TaskAsk
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import io.ktor.util.AttributeKey
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.onSubscription
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** What the routes need beside the graph. */
class Web(val graph: ServerGraph, val github: GitHub, val sessions: Sessions, val secure: Boolean = true, val testing: Boolean = false)

/** Who's asking: the website, signed in, or a phone by its token. */
sealed interface Client {
    data object Website : Client

    data class Phone(val id: String) : Client

    companion object {
        /** What the commands' record calls the website. */
        const val WEBSITE = "web"
    }
}

private val CLIENT = AttributeKey<Client>("client")
private val ApplicationCall.client: Client get() = attributes[CLIENT]

/** A problem, as a client reads it. */
@Serializable
private data class Problem(val error: String)

@Serializable
private data class Me(val signedIn: Boolean)

@Serializable
private data class StoreOut(val name: String, val version: Int, val revision: Long, val json: JsonElement)

/** The parent code's secret from the phone, or none: it's been taken away. */
@Serializable
private data class ParentCode(val secret: String? = null)

/** Which credentials the server holds, by name: never a value. */
@Serializable
private data class Credentials(val held: Set<String>)

@Serializable
private data class StateOut(val revision: Long, val stores: List<StoreOut>, val mode: String)

/** More than a page shrunk to what's sent ever comes to. */
private const val MAX_PHOTO_BYTES = 4 * 1024 * 1024L

/** Whether [bytes] starts as a JPEG does. */
private fun isJpeg(bytes: ByteArray) = bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

private const val SESSION = "__Host-session"
private const val OAUTH = "__Host-oauth"

/** The same cookies under a plain name, over plain HTTP on a PC, where the browser refuses a `__Host-` one. */
private fun cookie(web: Web, name: String) = if (web.secure) name else name.removePrefix("__Host-")

private val AUTH_LIMIT = RateLimitName("auth")

/** Under the minute most proxies give up on a quiet connection at. */
private val HEARTBEAT = 25.seconds
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** A branch of the routes that adds nothing to their path: `route("")` would be its parent, and what's installed there would hold for the open routes too. */
private object SignedInSelector : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation = RouteSelectorEvaluation.Transparent
}

/** Every route but the few that are open needs a client: a session, or a device's token (W-3, W-16). */
private fun signedIn(web: Web) = createRouteScopedPlugin("SignedIn") {
    onCall { call ->
        val bearer = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
        val phone = web.graph.devices.check(bearer)
        when {
            phone != null -> {
                // A client behind what the server speaks is told so, and carries on from what it holds till it's installed (W-16).
                val protocol = call.request.header(Protocol.HEADER)?.toIntOrNull()
                if (protocol == null || protocol > Protocol.VERSION || protocol < Protocol.VERSION - 1) {
                    call.respond(HttpStatusCode.Conflict, Problem("This client speaks protocol $protocol and the server ${Protocol.VERSION}"))
                    return@onCall
                }
                call.attributes.put(CLIENT, Client.Phone(phone))
            }
            web.sessions.check(call.request.cookies[cookie(web, SESSION)]) -> {
                // A cross-site form can't send this header: what changes something needs it.
                if (call.request.httpMethod != HttpMethod.Get && call.request.header("X-Requested-With") == null) {
                    call.respond(HttpStatusCode.Forbidden, Problem("Missing X-Requested-With"))
                    return@onCall
                }
                call.attributes.put(CLIENT, Client.Website)
            }
            else -> call.respond(HttpStatusCode.Unauthorized, Problem("Sign in first"))
        }
    }
}

/** The zone a phone says with each exchange, read before anything's applied or answered: a day is the owner's wherever they are. */
private fun ApplicationCall.heardZone(graph: ServerGraph) {
    if (client is Client.Phone) request.header(Protocol.ZONE_HEADER)?.let(graph.serverClock::setZone)
}

private fun ApplicationCall.setCookie(web: Web, name: String, value: String, maxAge: Int) {
    response.cookies.append(
        Cookie(cookie(web, name), value, CookieEncoding.RAW, maxAge = maxAge, path = "/", secure = web.secure, httpOnly = true, extensions = mapOf("SameSite" to "Lax")),
    )
}

/** The whole API (docs/plan_web/architecture.md §The server). A route turns a request into a call on the graph; none writes a store. */
fun Application.api(web: Web) {
    install(ContentNegotiation) { json(json) }
    install(XForwardedHeaders)
    install(SSE)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            // Never what a store held, nor a header: the kind of failure and where, not its message.
            Log.w(Log.TAG, "A request failed: ${cause::class.simpleName} at ${cause.stackTrace.firstOrNull { it.className.startsWith("com.thomaswcode") } ?: cause.stackTrace.firstOrNull()}")
            call.respond(HttpStatusCode.InternalServerError, Problem("Something went wrong"))
        }
    }
    install(RateLimit) {
        register(AUTH_LIMIT) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            requestKey { it.request.origin.remoteHost }
        }
    }
    routing {
        route("/api") {
            get("/health") { call.respondText("ok") }
            get("/version") { call.respond(web.graph.version()) }

            rateLimit(AUTH_LIMIT) {
                get("/auth/github") {
                    val state = Tokens.random()
                    val verifier = Tokens.random()
                    call.setCookie(web, OAUTH, "$state.$verifier", 600)
                    call.respondRedirect(web.github.authorizeUrl(state, Tokens.challenge(verifier)))
                }
                get("/auth/callback") {
                    val saved = call.request.cookies[cookie(web, OAUTH)]?.split(".")
                    val state = call.request.queryParameters["state"]
                    val code = call.request.queryParameters["code"]
                    call.setCookie(web, OAUTH, "", 0)
                    if (saved?.size != 2 || state == null || code == null || !Tokens.same(state, saved[0])) {
                        call.respond(HttpStatusCode.BadRequest, Problem("That sign-in didn't start here"))
                        return@get
                    }
                    if (!web.sessions.allows(web.github.userId(code, saved[1]))) {
                        call.respond(HttpStatusCode.Forbidden, Problem("This site is for one account"))
                        return@get
                    }
                    call.setCookie(web, SESSION, web.sessions.start(), (Sessions.LIFE_MS / 1000).toInt())
                    call.respondRedirect("/")
                }
                post("/devices/claim") {
                    val token = web.graph.devices.claim(call.receive<DeviceClaim>())
                    if (token == null) call.respond(HttpStatusCode.Forbidden, Problem("That code isn't right, or it's run out")) else call.respond(token)
                }
                if (web.testing) {
                    // A test's sign-in (the server run with DECRASTINATION_TEST): never in production, where `Main` refuses it.
                    post("/test/signin") {
                        call.setCookie(web, SESSION, web.sessions.start(), 3600)
                        call.respond(Me(true))
                    }
                    post("/test/commands") {
                        web.graph.commandsForATest()
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }

            get("/me") { call.respond(Me(web.sessions.check(call.request.cookies[cookie(web, SESSION)]))) }
            post("/auth/logout") {
                if (call.request.header("X-Requested-With") == null) {
                    call.respond(HttpStatusCode.Forbidden, Problem("Missing X-Requested-With"))
                    return@post
                }
                web.sessions.end(call.request.cookies[cookie(web, SESSION)])
                call.setCookie(web, SESSION, "", 0)
                call.respond(Me(false))
            }

            createChild(SignedInSelector).apply {
                install(signedIn(web))
                signedInRoutes(web)
            }
        }
    }
}

private fun Route.signedInRoutes(web: Web) {
    val graph = web.graph
    post("/devices/pair") {
        if (call.client !is Client.Website) return@post call.respond(HttpStatusCode.Forbidden, Problem("A device is paired from the website"))
        call.respond(graph.devices.pair())
    }
    get("/devices") {
        if (call.client !is Client.Website) return@get call.respond(HttpStatusCode.Forbidden, Problem("What's paired is the website's to see"))
        call.respond(graph.devices.list())
    }
    delete("/devices/{id}") {
        if (call.client !is Client.Website) return@delete call.respond(HttpStatusCode.Forbidden, Problem("A device is revoked from the website"))
        graph.devices.revoke(call.parameters["id"].orEmpty())
        call.respond(HttpStatusCode.NoContent)
    }

    get("/state") {
        call.heardZone(graph)
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0
        val state = graph.state(since)
        call.respond(StateOut(state.revision, state.stores.map { StoreOut(it.name, it.version, it.revision, json.parseToJsonElement(it.text)) }, graph.mode))
    }
    sse("/events") {
        // The heartbeat lets a dropped connection be seen, from either end; a comment, so a client hears nothing new in it.
        heartbeat {
            period = HEARTBEAT
            event = ServerSentEvent(comments = "still here")
        }
        // The revision it's at first: a client knows it's connected, and one that's reconnected catches what it missed.
        graph.revisions.onSubscription { emit(graph.db.currentRevision()) }.collect { send(ServerSentEvent(data = it.toString(), event = "revision")) }
    }
    post("/stores/seed") {
        val phone = call.client as? Client.Phone ?: return@post call.respond(HttpStatusCode.Forbidden, Problem("Only a phone seeds"))
        if (graph.mode == ServerGraph.COMMANDS) return@post call.respond(HttpStatusCode.Conflict, Problem("The server takes commands now"))
        call.respond(graph.seed(call.receive<SeedBatch>(), phone.id))
    }

    post("/commands") {
        call.heardZone(graph)
        // Mirror mode's writer is the phone, by its seeds: a command now would be undone by the next one.
        if (graph.mode != ServerGraph.COMMANDS) return@post call.respond(HttpStatusCode.Conflict, Problem("The server is mirroring the phone"))
        val device = (call.client as? Client.Phone)?.id ?: Client.WEBSITE
        val outcomes = graph.applier.apply(call.receive<CommandBatch>(), device)
        // Felix's answer, on its own time: the phone's message is in, whenever he gets to it.
        graph.answerSoon()
        call.respond(outcomes)
    }

    get("/credentials") {
        if (call.client !is Client.Website) return@get call.respond(HttpStatusCode.Forbidden, Problem("Credentials are the website's"))
        call.respond(Credentials(graph.credentials()))
    }
    post("/credentials") {
        if (call.client !is Client.Website) return@post call.respond(HttpStatusCode.Forbidden, Problem("Credentials are the website's"))
        graph.setCredentials(call.receive<Map<String, String>>())
        call.respond(Credentials(graph.credentials()))
    }

    get("/views/day") {
        val date = call.request.queryParameters["date"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        call.respond(graph.day(date))
    }
    get("/views/work") { call.respond(graph.work()) }
    post("/photos/tasks") {
        if (call.client !is Client.Website) return@post call.respond(HttpStatusCode.Forbidden, Problem("A photo's read from the website"))
        if (graph.mode != ServerGraph.COMMANDS) return@post call.respond(HttpStatusCode.Conflict, Problem("The server is mirroring the phone"))
        // By what it says it is first, so nothing that size is read: nothing in front of this limits a body.
        val length = call.request.contentLength()
        if (length == null || length > MAX_PHOTO_BYTES) return@post call.respond(HttpStatusCode.PayloadTooLarge, Problem("That photo's too big"))
        val jpeg = call.receive<ByteArray>()
        if (!isJpeg(jpeg)) return@post call.respond(HttpStatusCode.UnsupportedMediaType, Problem("That isn't a JPEG"))
        call.respond(graph.readPhoto(jpeg))
    }
    get("/views/settings") { call.respond(graph.settings()) }
    post("/parent-code") {
        // The phone's, as it's the phone that makes it: never set from the website, or the code would prove nothing.
        if (call.client !is Client.Phone) return@post call.respond(HttpStatusCode.Forbidden, Problem("The parent code is set on the phone"))
        graph.setParentCode(call.receive<ParentCode>().secret)
        call.respond(HttpStatusCode.NoContent)
    }
    get("/views/task/{id}") {
        val view = graph.task(call.parameters["id"].orEmpty()) ?: return@get call.respond(HttpStatusCode.NotFound, Problem("That task isn't here"))
        call.respond(view)
    }
    post("/tasks/{id}/ask") {
        if (call.client !is Client.Website) return@post call.respond(HttpStatusCode.Forbidden, Problem("A task is asked of from the website"))
        if (graph.mode != ServerGraph.COMMANDS) return@post call.respond(HttpStatusCode.Conflict, Problem("The server is mirroring the phone"))
        call.respond(graph.ask(call.parameters["id"].orEmpty(), call.receive<TaskAsk>()))
    }
}
