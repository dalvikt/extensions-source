package eu.kanade.tachiyomi.multisrc.pam

import android.util.Base64
import android.webkit.WebResourceResponse
import keiyoushi.utils.WebViewTimeoutException
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.SecureRandom
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal class SitePage(
    val page: JsonObject,
    val fetched: JsonElement? = null,
    val chapters: List<ChapterListItem>? = null,
    val reader: ReaderResult? = null,
    /** Reader v2 encrypted pages, fetched from the reader page in the WebView, one per page in order. */
    val pages: List<ByteArray>? = null,
) {
    fun fetched() = fetched ?: throw IOException("Missing API response")
}

/** Loads the site's pages in a WebView, one at a time, as its own readers do, and reads the site's data from them. */
internal class SitePages(
    baseUrl: String,
    private val client: () -> OkHttpClient,
    private val userAgent: () -> String,
) {
    private val baseHttpUrl = baseUrl.toHttpUrl()

    private val siteOrigins = setOf("${baseHttpUrl.scheme}://${baseHttpUrl.host}")

    private val mutex = Mutex()

    private var lastLoad: TimeMark? = null

    private val secureRandom = SecureRandom()

    private class Failure(val at: TimeMark, val challengeClearance: String?)

    /** Set when the site blocked or challenged us; loads fail fast for [BACKOFF], or until a challenge is passed in WebView. */
    @Volatile
    private var failure: Failure? = null

    private fun clearance() = client().cookieJar.loadForRequest(baseHttpUrl).firstOrNull { it.name == "cf_clearance" }?.value

    private fun failed(challenge: Boolean) {
        failure = Failure(TimeSource.Monotonic.markNow(), if (challenge) clearance().orEmpty() else null)
    }

    private fun Failure.isActive(): Boolean {
        if (at.elapsedNow() >= BACKOFF) return false
        return challengeClearance == null || clearance().let { it == null || it == challengeClearance }
    }

    /** Fails fast while the site is blocking us, and paces loads like a reader opening pages. */
    private suspend fun awaitTurn() {
        if (failure?.isActive() == true) throw IOException(SOLVE_IN_WEBVIEW)
        lastLoad?.let { delay(LOAD_INTERVAL - it.elapsedNow()) }
        lastLoad = TimeSource.Monotonic.markNow()
    }

    private fun asset(name: String) = javaClass.getResource("/assets/$name")!!.readText()

    /** Loads [url] and reads its Inertia page data, and the site's own API calls made from that page. */
    suspend fun load(url: String, request: SiteRequest = SiteRequest()): SitePage = mutex.withLock {
        awaitTurn()

        val bridge = "k" + randomHex(8)
        val readScript = "(${asset("site.js")})(\"$bridge\", ${request.toJsonString()})"
        val visibleScript = "(${asset("visible.js")})()"
        var challengePolls = 0
        val result = try {
            runWebView<SitePage>(timeout = WEBVIEW_TIMEOUT) {
                userAgent = this@SitePages.userAgent()
                blockImages = true
                interceptRequest { webRequest ->
                    val host = webRequest.url.host
                    if (host != null && host != baseHttpUrl.host && host != TURNSTILE_HOST) return@interceptRequest blockedResponse()
                    val path = webRequest.url.path.orEmpty()
                    if (UNUSED_PATHS.any(path::startsWith)) return@interceptRequest blockedResponse()
                    if (STYLE_EXTENSIONS.any(path::endsWith)) return@interceptRequest emptyResponse("text/css")
                    null
                }
                webMessageBridge(bridge, siteOrigins) { posted, sender ->
                    if (sender.isMainFrame == false) return@webMessageBridge
                    val message = posted.parseAs<SiteMessage>()
                    if (message.error != null) {
                        if (message.status == 403 || message.status == 429) failed(challenge = false)
                        return@webMessageBridge reject(IOException(message.error))
                    }
                    val page = message.page ?: return@webMessageBridge reject(IOException("Missing page data"))
                    resolve(SitePage(page, message.fetched, message.chapters))
                }
                onReceivedError { webRequest, error ->
                    if (webRequest.isForMainFrame) reject(IOException(error.description.toString()))
                }
                injectAtDocumentStart(visibleScript, siteOrigins)
                poll(POLL_INTERVAL) {
                    evaluateJs(readScript) {
                        if (it != CHALLENGE_RESULT) {
                            challengePolls = 0
                        } else if (POLL_INTERVAL * ++challengePolls > CHALLENGE_WAIT) {
                            failed(challenge = true)
                            reject(IOException(SOLVE_IN_WEBVIEW))
                        }
                    }
                }
                loadUrl(url)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: WebViewTimeoutException) {
            throw challengeOr(challengePolls, "The site's page did not load, try opening it in WebView", e)
        } catch (e: Throwable) {
            throw IOException(e.message, e)
        }
        failure = null
        result
    }

    /**
     * Loads the chapter page in a WebView as the site's reader does. For reader v2 it reads the content
     * key the reader derives (from a wrapper over the decrypt worker) and fetches the encrypted pages
     * from the page itself, as the reader does, so none of it reaches the site over a non-browser client.
     */
    suspend fun loadReader(url: String): SitePage = mutex.withLock {
        awaitTurn()

        val readerScript = "(${asset("reader-read.js")})()"
        val visibleScript = "(${asset("visible.js")})()"
        var challengePolls = 0
        val lock = Any()
        var page: JsonObject? = null
        var readerV2 = false
        var pageCount = 0
        var capture: ReaderCapture? = null
        var pagesStarted = false
        var draining = false

        val result = try {
            runWebView<SitePage>(timeout = READER_TIMEOUT) {
                userAgent = this@SitePages.userAgent()
                blockImages = true

                fun drainPages(total: Int) {
                    val pages = arrayOfNulls<ByteArray>(total)
                    fun drainFrom(index: Int) {
                        if (index >= total) {
                            val manifest = ReaderResult.Manifest(capture!!.base, total, listOf(capture!!.width))
                            resolve(SitePage(page!!, reader = ReaderResult(manifest, capture!!.contentKey), pages = pages.map { it!! }))
                            return
                        }
                        evaluateJs("(${asset("reader-drain.js")})($index)") { json ->
                            val encoded = json.takeIf { it != "null" }?.parseAs<String>()
                                ?: return@evaluateJs reject(IOException("Page ${index + 1} missing"))
                            pages[index] = Base64.decode(encoded, Base64.DEFAULT)
                            drainFrom(index + 1)
                        }
                    }
                    drainFrom(0)
                }

                fun advance() = synchronized(lock) {
                    val props = page ?: return@synchronized
                    if (!readerV2) return@synchronized resolve(SitePage(props))
                    val cap = capture ?: return@synchronized
                    if (pageCount <= 0 || pagesStarted) return@synchronized
                    pagesStarted = true
                    val fetch = "(${asset("reader-pages.js")})(${cap.base.toJsonString()}, ${cap.width}, $pageCount, ${cap.headers.toJsonString()})"
                    evaluateJs(fetch)
                }

                interceptRequest { webRequest ->
                    val host = webRequest.url.host
                    if (host != null && host != baseHttpUrl.host && host != TURNSTILE_HOST) return@interceptRequest blockedResponse()
                    val path = webRequest.url.path.orEmpty()
                    // The wrapper re-imports the real worker with this marker; let it through.
                    if (webRequest.url.getQueryParameter(ORIGINAL_WORKER_PARAM) != null) return@interceptRequest null
                    if (UNUSED_PATHS.any(path::startsWith)) return@interceptRequest blockedResponse()
                    if (path.startsWith(CAPTURE_PATH)) {
                        synchronized(lock) {
                            if (capture == null) {
                                capture = runCatching {
                                    // The path is already percent-decoded
                                    path.removePrefix(CAPTURE_PATH).parseAs<ReaderCapture>()
                                }.getOrNull()
                            }
                        }
                        advance()
                        return@interceptRequest emptyResponse("text/plain")
                    }
                    // Serve the decrypt worker through a wrapper that reads the key the site derives.
                    // A worker's top-level module request carries no Origin, unlike the page's own scripts.
                    if (WORKER_SCRIPT_REGEX.matches(path) && webRequest.requestHeaders.keys.none { it.equals("Origin", ignoreCase = true) }) {
                        return@interceptRequest workerWrapper(path.substringAfterLast('/'))
                    }
                    null
                }
                onReceivedError { webRequest, error ->
                    if (webRequest.isForMainFrame) reject(IOException(error.description.toString()))
                }
                injectAtDocumentStart(visibleScript, siteOrigins)
                poll(POLL_INTERVAL) {
                    if (!pagesStarted) {
                        evaluateJs(readerScript) { json ->
                            val state = json.takeIf { it != "null" }?.parseAs<ReaderState>() ?: return@evaluateJs
                            when (state.s) {
                                "challenge" -> if (POLL_INTERVAL * ++challengePolls > CHALLENGE_WAIT) {
                                    failed(challenge = true)
                                    reject(IOException(SOLVE_IN_WEBVIEW))
                                }
                                "error" -> {
                                    if (state.status == 403 || state.status == 429) failed(challenge = false)
                                    reject(IOException("HTTP error ${state.status}"))
                                }
                                "ready" -> {
                                    synchronized(lock) {
                                        challengePolls = 0
                                        page = state.page
                                        readerV2 = state.v2
                                        pageCount = state.count
                                    }
                                    advance()
                                }
                                else -> challengePolls = 0
                            }
                        }
                    } else if (!draining) {
                        evaluateJs("(${asset("reader-drain.js")})(-1)") { json ->
                            val status = json.takeIf { it != "null" }?.parseAs<ReaderPagesStatus>() ?: return@evaluateJs
                            when {
                                status.error != null -> {
                                    if (status.error.endsWith("403") || status.error.endsWith("429")) failed(challenge = false)
                                    reject(IOException(status.error))
                                }
                                status.total > 0 && status.ready == status.total -> {
                                    draining = true
                                    drainPages(status.total)
                                }
                            }
                        }
                    }
                }
                loadUrl(url)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: WebViewTimeoutException) {
            throw challengeOr(challengePolls, "The site's reader did not load, try opening the chapter in WebView", e)
        } catch (e: Throwable) {
            throw IOException(e.message, e)
        }
        failure = null
        result
    }

    private fun challengeOr(challengePolls: Int, message: String, cause: Throwable): IOException {
        if (challengePolls > 0) {
            failed(challenge = true)
            return IOException(SOLVE_IN_WEBVIEW, cause)
        }
        return IOException(message, cause)
    }

    private fun workerWrapper(scriptName: String): WebResourceResponse {
        val wrapper = asset("reader-worker.js")
            .replace("__REAL_WORKER__", "./$scriptName?$ORIGINAL_WORKER_PARAM=1")
            .replace("__CAPTURE_PATH__", CAPTURE_PATH)
        return WebResourceResponse(
            "text/javascript",
            "UTF-8",
            200,
            "OK",
            emptyMap(),
            ByteArrayInputStream(wrapper.toByteArray()),
        )
    }

    private fun randomHex(byteCount: Int): String = ByteArray(byteCount).also(secureRandom::nextBytes).joinToString("") { "%02x".format(it) }

    private fun emptyResponse(mimeType: String) = WebResourceResponse(mimeType, null, 200, "OK", null, ByteArrayInputStream(ByteArray(0)))

    private fun blockedResponse() = WebResourceResponse("text/plain", null, 403, "Blocked", null, ByteArrayInputStream(ByteArray(0)))
}

private const val TURNSTILE_HOST = "challenges.cloudflare.com"
private val POLL_INTERVAL = 250.milliseconds
private val LOAD_INTERVAL = 2.seconds
private val BACKOFF = 5.minutes
private val CHALLENGE_WAIT = 15.seconds
private val WEBVIEW_TIMEOUT = 60.seconds
private val READER_TIMEOUT = 180.seconds

private const val CHALLENGE_RESULT = "\"challenge\""

// Comment stickers, Cloudflare's prefetch hints, the favicon and the site's analytics
private val UNUSED_PATHS = listOf("/api/v1/stickers", "/cdn-cgi/speculation", "/favicon.ico", "/gmetrics/")
private val STYLE_EXTENSIONS = listOf(".css", ".woff2", ".woff", ".ttf")
private val WORKER_SCRIPT_REGEX = Regex("""/build/assets/[\w-]+\.js""")
private const val CAPTURE_PATH = "/__pam_capture__/"
private const val ORIGINAL_WORKER_PARAM = "pam_original"
private const val SOLVE_IN_WEBVIEW = "Open in WebView to pass the site's verification"
