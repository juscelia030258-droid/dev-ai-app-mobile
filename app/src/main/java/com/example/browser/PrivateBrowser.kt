package com.example.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.URI
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayInputStream
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray

data class BrowserPage(
    val requestedUrl: String,
    val finalUrl: String,
    val title: String,
    val text: String,
    val screenshotPath: String? = null,
    val capturedAt: Long = System.currentTimeMillis(),
    val links: List<BrowserLink> = emptyList()
)

data class BrowserLink(
    val title: String,
    val url: String
)

/**
 * Private, app-owned browser. It uses an isolated WebView with cookies and file
 * access disabled. This first version is intentionally read-only: it can open
 * public pages and extract visible text, but cannot submit forms or sign in.
 */
class PrivateBrowser(context: Context) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()

    suspend fun searchAndRead(query: String): Result<BrowserPage> {
        val searchUrl = "https://html.duckduckgo.com/html/?q=" +
            java.net.URLEncoder.encode(query.take(200), "UTF-8")
        val searchPage = openAndRead(searchUrl).getOrElse { return Result.failure(it) }
        val sourcePages = mutableListOf<BrowserPage>()
        for (link in searchPage.links.take(8)) {
            val source = openAndRead(link.url).getOrNull()
            if (source != null && source.text.isNotBlank()) sourcePages += source
            if (sourcePages.size >= 2) break
        }
        if (sourcePages.isEmpty()) return Result.success(searchPage)
        val combined = buildString {
            appendLine("Resumo da página de resultados:")
            appendLine(searchPage.text.take(2500))
            sourcePages.forEachIndexed { index, page ->
                appendLine()
                appendLine("FONTE ${index + 1}: ${page.title}")
                appendLine("URL: ${page.finalUrl}")
                appendLine(page.text.take(5500))
            }
        }.take(16000)
        return Result.success(
            searchPage.copy(
                title = "Pesquisa: $query",
                text = combined,
                links = sourcePages.map { BrowserLink(it.title, it.finalUrl) }
            )
        )
    }

    suspend fun openAndRead(url: String): Result<BrowserPage> = mutex.withLock {
        try {
            val safeUrl = validatePublicUrl(url)
            Result.success(withTimeout(30_000L) { browseOnMain(safeUrl) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private suspend fun validatePublicUrl(raw: String): String = withContext(Dispatchers.IO) {
        val uri = runCatching { URI(raw.trim()) }.getOrElse {
            throw IllegalArgumentException("URL inválida.")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "https" || scheme == "http") { "O navegador aceita somente URLs HTTP ou HTTPS." }
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')
            ?: throw IllegalArgumentException("A URL não possui um host válido.")
        require(host.isNotBlank() && host != "localhost" && !host.endsWith(".localhost")) {
            "O navegador não acessa endereços locais."
        }
        require(!host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) && !host.contains(":")) {
            "Endereços IP diretos não são aceitos; use o domínio público do site."
        }
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrElse {
            throw IllegalArgumentException("Não foi possível resolver o domínio: $host")
        }
        require(addresses.isNotEmpty() && addresses.all { isPublicAddress(it) }) {
            "O navegador bloqueou um domínio que resolve para uma rede privada ou reservada."
        }
        uri.toASCIIString()
    }

    private fun isPublicAddress(address: InetAddress): Boolean =
        !address.isAnyLocalAddress &&
        !address.isLoopbackAddress &&
        !address.isLinkLocalAddress &&
        !address.isSiteLocalAddress &&
        !address.isMulticastAddress

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun browseOnMain(url: String): BrowserPage {
        val result = CompletableDeferred<BrowserPage>()
        val webView = withContext(Dispatchers.Main) {
            WebView(appContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.loadsImagesAutomatically = true
                settings.blockNetworkImage = false
                settings.setGeolocationEnabled(false)
                settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                CookieManager.getInstance().setAcceptCookie(false)
                webChromeClient = WebChromeClient()
            }
        }

        try {
            withContext(Dispatchers.Main) {
                val allowedHost = URI(url).host?.lowercase(Locale.ROOT)
                webView.webViewClient = object : WebViewClient() {
                    // Enforce read-only browsing and block private-network targets for every
                    // subresource/XHR request, not only top-level navigation.
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): android.webkit.WebResourceResponse? {
                        val target = request?.url ?: return blockedResponse("Requisição inválida.")
                        val scheme = target.scheme?.lowercase(Locale.ROOT)
                        if (scheme != "https" && scheme != "http") {
                            return blockedResponse("Esquema de URL bloqueado.")
                        }
                        if (!request.method.equals("GET", ignoreCase = true)) {
                            return blockedResponse("O Private Browser permite somente requisições GET.")
                        }
                        val host = target.host?.lowercase(Locale.ROOT)?.trimEnd('.')
                            ?: return blockedResponse("Host inválido.")
                        if (host == "localhost" || host.endsWith(".localhost")) {
                            return blockedResponse("Acesso a host local bloqueado.")
                        }
                        val addresses = runCatching { InetAddress.getAllByName(host).toList() }
                            .getOrElse { return blockedResponse("Não foi possível validar o destino.") }
                        if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) {
                            return blockedResponse("Acesso a rede privada ou reservada bloqueado.")
                        }
                        return null
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val targetUri = request?.url ?: return true
                        val scheme = targetUri.scheme?.lowercase(Locale.ROOT)
                        val targetHost = targetUri.host?.lowercase(Locale.ROOT)
                        // Keep top-level navigation on the validated public host.
                        return (scheme != "https" && scheme != "http") || targetHost != allowedHost
                    }

                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        if (result.isCompleted) return
                        val script = """
                            (function() {
                              const clean = document.cloneNode(true);
                              clean.querySelectorAll('script,style,noscript,svg,canvas,nav,footer,header,form').forEach(e => e.remove());
                              const links = Array.from(document.querySelectorAll('a.result__a'))
                                .slice(0, 8)
                                .map(a => ({title: (a.innerText || '').trim(), href: a.href || ''}))
                                .filter(a => a.title && a.href);
                              return JSON.stringify({
                                title: document.title || '',
                                url: location.href || '',
                                text: (clean.body ? clean.body.innerText : '').slice(0, 18000),
                                links: links
                              });
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(script) { raw ->
                            if (result.isCompleted) return@evaluateJavascript
                            val decoded = runCatching { Json.parseToJsonElement(raw.orEmpty()).jsonPrimitive.content }.getOrDefault("")
                            val documentJson = runCatching { Json.parseToJsonElement(decoded).jsonObject }.getOrNull()
                            val title = documentJson?.get("title")?.jsonPrimitive?.content.orEmpty()
                            val finalUrl = documentJson?.get("url")?.jsonPrimitive?.content.orEmpty().ifBlank { finishedUrl.orEmpty() }
                            val text = documentJson?.get("text")?.jsonPrimitive?.content.orEmpty()
                            val links = documentJson?.get("links")?.jsonArray?.mapNotNull { item ->
                                val link = item.jsonObject
                                val label = link["title"]?.jsonPrimitive?.content.orEmpty().trim()
                                val href = normalizeSearchResultUrl(link["href"]?.jsonPrimitive?.content.orEmpty())
                                if (label.isNotBlank() && href != null) BrowserLink(label.take(160), href) else null
                            }.orEmpty()
                            if (text.isBlank() && title.isBlank()) {
                                result.completeExceptionally(IllegalStateException("A página abriu, mas não foi possível extrair seu conteúdo."))
                            } else {
                                val screenshot = view?.let { captureScreenshot(it) }
                                result.complete(BrowserPage(url, finalUrl, title, text, screenshot, links = links))
                            }
                        } ?: result.completeExceptionally(IllegalStateException("WebView não conseguiu ler a página."))
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: android.webkit.WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true && !result.isCompleted) {
                            result.completeExceptionally(
                                IllegalStateException("Falha ao abrir a página: ${error?.description ?: "erro de rede"}")
                            )
                        }
                    }
                }
                webView.loadUrl(url)
            }
            return result.await()
        } finally {
            withContext(Dispatchers.Main) {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.removeAllViews()
                webView.destroy()
            }
        }
    }

    private fun normalizeSearchResultUrl(raw: String): String? = runCatching {
        val candidate = URI(raw)
        val redirected = candidate.rawQuery
            ?.split("&")
            ?.firstOrNull { it.startsWith("uddg=") }
            ?.substringAfter("uddg=")
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        val resolved = redirected ?: raw
        val uri = URI(resolved)
        if (uri.scheme != "https" && uri.scheme != "http") return null
        if (uri.host.isNullOrBlank()) return null
        uri.toASCIIString()
    }.getOrNull()

    private fun blockedResponse(message: String) =
        android.webkit.WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Forbidden",
            emptyMap(),
            ByteArrayInputStream(message.toByteArray(Charsets.UTF_8))
        )

    private fun captureScreenshot(webView: WebView): String? = runCatching {
        val width = 720
        val height = 1280
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        webView.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        webView.draw(Canvas(bitmap))
        val output = File(appContext.cacheDir, "private_browser_${System.currentTimeMillis()}.jpg")
        FileOutputStream(output).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 78, stream)
        }
        bitmap.recycle()
        output.absolutePath
    }.getOrNull()

    private fun jsonField(json: String?, key: String): String {
        if (json.isNullOrBlank()) return ""
        return runCatching {
            Json.parseToJsonElement(json).jsonObject[key]?.jsonPrimitive?.content.orEmpty()
        }.getOrDefault("")
    }
}
