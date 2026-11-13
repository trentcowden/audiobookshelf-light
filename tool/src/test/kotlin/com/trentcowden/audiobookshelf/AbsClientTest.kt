package com.trentcowden.audiobookshelf

import com.trentcowden.audiobookshelf.api.AbsClient
import com.trentcowden.audiobookshelf.api.AbsException
import com.trentcowden.audiobookshelf.api.PermanentDownloadException
import com.trentcowden.audiobookshelf.api.SignedOutException
import com.trentcowden.audiobookshelf.api.SingleSignOnOnlyException
import com.trentcowden.audiobookshelf.storage.AuthState
import com.trentcowden.audiobookshelf.storage.AuthStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The API client against a scripted fake server: sign-in variants, token
 * refresh, odd server setups, and download resume.
 */
class AbsClientTest {
    private val requests = mutableListOf<HttpRequestData>()
    private var handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { error("no handler") }
    private val client = AbsClient(
        MockEngine { request ->
            requests += request
            handler(request)
        },
    )

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private fun MockRequestHandleScope.ok(body: String) = respond(body, HttpStatusCode.OK, json)
    private fun MockRequestHandleScope.status(code: HttpStatusCode) = respond("", code)
    private val HttpRequestData.path get() = url.encodedPath
    private fun HttpRequestData.bodyText() = (body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()

    private val statusOk = """{"app":"audiobookshelf","serverVersion":"2.30.0","authMethods":["local"]}"""
    private val signedIn = AuthState("https://abs.example.com", "trent", "access-1", "refresh-1")

    @BeforeTest
    fun signOut() {
        freshAuthStore()
    }

    @Test
    fun logsInAndUsesTheTokensTheServerReturns() = runTest {
        handler = { req ->
            when (req.path) {
                "/status" -> ok(statusOk)
                "/login" -> {
                    assertEquals("true", req.headers["x-return-tokens"])
                    ok("""{"user":{"username":"trent","token":"legacy","accessToken":"a1","refreshToken":"r1"}}""")
                }
                else -> status(HttpStatusCode.NotFound)
            }
        }
        val auth = client.login("abs.example.com", "trent", "pw")
        assertEquals(AuthState("https://abs.example.com", "trent", "a1", "r1"), auth)
    }

    @Test
    fun olderServersGiveOneLongLivedToken() = runTest {
        handler = { req ->
            when (req.path) {
                "/status" -> ok("""{"app":"audiobookshelf","serverVersion":"2.17.0"}""")
                "/login" -> ok("""{"user":{"username":"trent","token":"legacy"}}""")
                else -> status(HttpStatusCode.NotFound)
            }
        }
        val auth = client.login("abs.example.com", "trent", "pw")
        assertEquals("legacy", auth.accessToken)
        assertNull(auth.refreshToken)
    }

    @Test
    fun keepsTheSubPathOfAServerBehindAReverseProxy() = runTest {
        handler = { req ->
            when (req.path) {
                "/audiobookshelf/status" -> ok(statusOk)
                "/audiobookshelf/login" -> ok("""{"user":{"username":"trent","accessToken":"a1"}}""")
                else -> status(HttpStatusCode.NotFound)
            }
        }
        val auth = client.login("example.com/audiobookshelf/", "trent", "pw")
        assertEquals("https://example.com/audiobookshelf", auth.serverUrl)
    }

    @Test
    fun fallsBackToPlainHttpForHomeServersTypedWithoutAScheme() = runTest {
        handler = { req ->
            if (req.url.protocol.name == "https") throw IOException("connection refused")
            when (req.path) {
                "/status" -> ok(statusOk)
                "/login" -> ok("""{"user":{"username":"trent","accessToken":"a1"}}""")
                else -> status(HttpStatusCode.NotFound)
            }
        }
        assertEquals("http://192.168.1.5:13378", client.login("192.168.1.5:13378", "trent", "pw").serverUrl)
        // Typed with https://: no silent downgrade.
        assertFailsWith<AbsException> { client.login("https://192.168.1.5:13378", "trent", "pw") }
    }

    @Test
    fun explainsSignInProblems() = runTest {
        handler = { req ->
            when (req.path) {
                "/status" -> ok(statusOk)
                else -> status(HttpStatusCode.Unauthorized)
            }
        }
        assertEquals("Wrong username or password.", assertFailsWith<AbsException> { client.login("x.com", "u", "bad") }.message)

        requests.clear()
        handler = { ok("""{"app":"audiobookshelf","serverVersion":"2.30.0","authMethods":["openid"]}""") }
        assertFailsWith<SingleSignOnOnlyException> { client.login("x.com", "u", "pw") }
        assertTrue(requests.none { it.path == "/login" }, "no password is sent to an SSO-only server")

        handler = { ok("""{"app":"audiobookshelf","serverVersion":"1.9.0"}""") }
        assertContains(assertFailsWith<AbsException> { client.login("x.com", "u", "pw") }.message!!, "Update it")

        handler = { ok("""{"app":"jellyfin"}""") }
        assertContains(assertFailsWith<AbsException> { client.login("x.com", "u", "pw") }.message!!, "doesn't look like")
    }

    @Test
    fun signsInWithAnApiKey() = runTest {
        handler = { req ->
            when (req.path) {
                "/status" -> ok("""{"app":"audiobookshelf","serverVersion":"2.30.0","authMethods":["openid"]}""")
                "/api/me" -> {
                    assertEquals("Bearer key-123", req.headers[HttpHeaders.Authorization])
                    ok("""{"username":"bethany","mediaProgress":[]}""")
                }
                else -> status(HttpStatusCode.NotFound)
            }
        }
        val auth = client.loginWithApiKey("abs.example.com", "  key-123 ")
        assertEquals(AuthState("https://abs.example.com", "bethany", "key-123", null, usesApiKey = true), auth)

        handler = { req -> if (req.path == "/status") ok(statusOk) else status(HttpStatusCode.Unauthorized) }
        assertContains(assertFailsWith<AbsException> { client.loginWithApiKey("abs.example.com", "bad") }.message!!, "API key")
    }

    @Test
    fun refreshesAnExpiredAccessTokenOnce() = runTest {
        AuthStore.save(signedIn)
        handler = { req ->
            when {
                req.path == "/auth/refresh" -> {
                    assertEquals("refresh-1", req.headers["x-refresh-token"])
                    ok("""{"user":{"username":"trent","accessToken":"access-2","refreshToken":"refresh-2"}}""")
                }
                req.headers[HttpHeaders.Authorization] == "Bearer access-1" -> status(HttpStatusCode.Unauthorized)
                req.path == "/api/me" -> ok("""{"username":"trent","mediaProgress":[{"libraryItemId":"b1","currentTime":5.0}]}""")
                else -> status(HttpStatusCode.NotFound)
            }
        }
        assertEquals(5.0, client.mediaProgress()["b1"]?.currentTime)
        assertEquals("access-2", AuthStore.current?.accessToken)
        assertEquals("refresh-2", AuthStore.current?.refreshToken)
    }

    @Test
    fun aRejectedRefreshSignsOut() = runTest {
        AuthStore.save(signedIn)
        handler = { status(HttpStatusCode.Unauthorized) }
        assertFailsWith<SignedOutException> { client.mediaProgress() }
        assertNull(AuthStore.current)
        assertEquals("https://abs.example.com", AuthStore.lastSignIn?.serverUrl)
    }

    @Test
    fun aRevokedApiKeySignsOutWithoutTryingToRefresh() = runTest {
        AuthStore.save(AuthState("https://abs.example.com", "trent", "key", null, usesApiKey = true))
        handler = { status(HttpStatusCode.Unauthorized) }
        assertFailsWith<SignedOutException> { client.mediaProgress() }
        assertTrue(requests.none { it.path == "/auth/refresh" })
        assertNull(AuthStore.current)
    }

    @Test
    fun readsLibrariesInEitherShape() = runTest {
        AuthStore.save(signedIn)
        handler = { ok("""{"libraries":[{"id":"l1","name":"Books","mediaType":"book"},{"id":"p","name":"Pods","mediaType":"podcast"}]}""") }
        assertEquals(listOf("l1"), client.bookLibraries().map { it.id })
        handler = { ok("""[{"id":"l2","name":"Books","mediaType":"book"}]""") }
        assertEquals(listOf("l2"), client.bookLibraries().map { it.id })
    }

    @Test
    fun pagesThroughBigLibraries() = runTest {
        AuthStore.save(signedIn)
        handler = { req ->
            val page = req.url.parameters["page"]!!.toInt()
            val count = if (page < 2) 200 else 17
            val items = (0 until count).joinToString(",") { """{"id":"p$page-$it","media":{"metadata":{"title":"t"}}}""" }
            ok("""{"results":[$items],"total":417}""")
        }
        assertEquals(417, client.allItems("l1").size)
        assertEquals(3, requests.size)
    }

    @Test
    fun resumesADownloadWithARangeRequest() = runTest {
        AuthStore.save(signedIn)
        val part = File(tempDir(), "1.mp3.part").apply { writeBytes(ByteArray(400) { 1 }) }
        handler = { req ->
            assertEquals("bytes=400-", req.headers[HttpHeaders.Range])
            respond(
                ByteArray(600) { 2 },
                HttpStatusCode.PartialContent,
                headersOf(HttpHeaders.ContentRange, "bytes 400-999/1000"),
            )
        }
        assertEquals(1000, client.downloadFile("b1", "ino1", part) { _, _ -> })
        assertEquals(1000, part.length())
        assertEquals(1.toByte(), part.readBytes()[399])
        assertEquals(2.toByte(), part.readBytes()[400])
    }

    @Test
    fun aDownloadThatWasAlreadyCompleteIsDone() = runTest {
        AuthStore.save(signedIn)
        val part = File(tempDir(), "1.mp3.part").apply { writeBytes(ByteArray(1000)) }
        handler = { respond("", HttpStatusCode.RequestedRangeNotSatisfiable, headersOf(HttpHeaders.ContentRange, "bytes */1000")) }
        assertEquals(1000, client.downloadFile("b1", "ino1", part) { _, _ -> })
    }

    @Test
    fun explainsMissingDownloadPermission() = runTest {
        AuthStore.save(signedIn)
        handler = { status(HttpStatusCode.Forbidden) }
        val e = assertFailsWith<PermanentDownloadException> {
            client.downloadFile("b1", "ino1", File(tempDir(), "x.part")) { _, _ -> }
        }
        assertContains(e.message!!, "allowed to download")
    }

    @Test
    fun marksNotStartedByDeletingTheProgressRecord() = runTest {
        AuthStore.save(signedIn)
        handler = { req ->
            when {
                req.method == HttpMethod.Get -> ok("""{"id":"prog-9","libraryItemId":"b1","currentTime":50.0,"duration":100.0}""")
                req.method == HttpMethod.Delete -> ok("{}")
                else -> status(HttpStatusCode.BadRequest)
            }
        }
        client.deleteProgress("b1")
        assertEquals("/api/me/progress/prog-9", requests.last { it.method == HttpMethod.Delete }.path)
    }

    @Test
    fun marksFinishedAtTheEnd() = runTest {
        AuthStore.save(signedIn)
        handler = { req ->
            if (req.method == HttpMethod.Patch) ok("{}")
            else ok("""{"libraryItemId":"b1","currentTime":100.0,"isFinished":true,"lastUpdate":7}""")
        }
        val progress = client.setFinished("b1", 100.0)
        assertTrue(progress!!.isFinished)
        val body = requests.first { it.method == HttpMethod.Patch }.bodyText()
        assertContains(body, "\"isFinished\":true")
        assertContains(body, "\"currentTime\":100.0")
    }

    @Test
    fun explainsUntrustedCertificates() {
        val e = javax.net.ssl.SSLHandshakeException("handshake").apply {
            initCause(java.security.cert.CertPathValidatorException("Trust anchor for certification path not found."))
        }
        assertContains(AbsClient.sslMessage(e), "self-signed")
        assertContains(AbsClient.sslMessage(javax.net.ssl.SSLException("other")), "http://")
    }

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(AbsClient.compareVersions("2.10.0", "2.9.1") > 0)
        assertEquals(0, AbsClient.compareVersions("v2.0.0", "2.0"))
        assertTrue(AbsClient.compareVersions("1.9.9-beta", "2.0.0") < 0)
    }
}
