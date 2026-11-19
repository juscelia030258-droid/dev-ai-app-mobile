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
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class BrowserPage(
    val requestedUrl: String,
    val finalUrl: String,
    val title: String,
    val text: String,
    val screenshotPath: String? = null,
    val capturedAt: Long = System.currentTimeMillis()
)

/**
 * Private, app-owned browser. It uses an isolated WebView with cookies and file
 * access disabled. This first version is intentionally read-only: it can open
 * public pages and extract visible text, but cannot submit forms or sign in.
 */
class PrivateBrowser(context: Context) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()

    suspend fun searchAndRead(query: String): Result<BrowserPage> =
        openAndRead("https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(query.take(200), "UTF-8"))

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
                              return JSON.stringify({
                                title: document.title || '',
                                url: location.href || '',
                                text: (clean.body ? clean.body.innerText : '').slice(0, 18000)
                              });
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(script) { raw ->
                            if (result.isCompleted) return@evaluateJavascript
                            val decoded = runCatching { Json.parseToJsonElement(raw.orEmpty()).jsonPrimitive.content }.getOrDefault("")
                            val fields = Regex("\\{.*}", RegexOption.DOT_MATCHES_ALL).find(decoded)?.value
                            val title = jsonField(fields, "title")
                            val finalUrl = jsonField(fields, "url").ifBlank { finishedUrl.orEmpty() }
                            val text = jsonField(fields, "text")
                            if (text.isBlank() && title.isBlank()) {
                                result.completeExceptionally(IllegalStateException("A página abriu, mas não foi possível extrair seu conteúdo."))
                            } else {
                                val screenshot = view?.let { captureScreenshot(it) }
                                result.complete(BrowserPage(url, finalUrl, title, text, screenshot))
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
            result.await()
        } finally {
            withContext(Dispatchers.Main) {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.removeAllViews()
                webView.destroy()
            }
        }
    }

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
