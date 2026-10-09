package com.vijaysetu.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.webkit.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var offlineLayout: LinearLayout
    private lateinit var btnRetry: Button

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // File picker launcher supporting single file, multiple files, and null-safe cancellation
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (filePathCallback != null) {
            var uris: Array<Uri>? = null
            if (result.resultCode == RESULT_OK && result.data != null) {
                val data = result.data
                val singleUri = data?.data
                val clipData = data?.clipData

                uris = when {
                    singleUri != null -> arrayOf(singleUri)
                    clipData != null -> {
                        val count = clipData.itemCount
                        Array(count) { i -> clipData.getItemAt(i).uri }
                    }
                    else -> WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
                }
            }
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        progressBar = findViewById(R.id.progressBar)
        offlineLayout = findViewById(R.id.offlineLayout)
        btnRetry = findViewById(R.id.btnRetry)

        setupWebView()
        setupSwipeRefresh()
        setupBackPressHandler()

        btnRetry.setOnClickListener {
            if (isNetworkAvailable()) {
                offlineLayout.visibility = View.GONE
                webView.visibility = View.VISIBLE
                webView.reload()
            }
        }

        val targetUrl = getString(R.string.default_web_url)
        if (isNetworkAvailable()) {
            webView.loadUrl(targetUrl)
        } else {
            showOfflineView()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.setSupportZoom(false)
        settings.textZoom = 100
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.mediaPlaybackRequiresUserGesture = false

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAcceptThirdPartyCookies(webView, true)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        val defaultUserAgent = settings.userAgentString
        settings.userAgentString = "$defaultUserAgent VijaySetuAndroidApp/1.0"

        // Native JavaScript bridge for Blob and Base64 file downloads
        webView.addJavascriptInterface(BlobDownloaderInterface(this), "AndroidBlobDownloader")

        // Download Listener for network attachment links
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            downloadHttpFile(url, userAgent, contentDisposition, mimetype)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                offlineLayout.visibility = View.GONE
                webView.visibility = View.VISIBLE
                injectBlobInterceptionScript()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                swipeRefreshLayout.isRefreshing = false
                injectBlobInterceptionScript()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true && !isNetworkAvailable()) {
                    showOfflineView()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                if (!isNetworkAvailable()) {
                    showOfflineView()
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleUrlNavigation(url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                return handleUrlNavigation(url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.progress = newProgress
                if (newProgress == 100) {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                try {
                    request?.grant(request.resources)
                } catch (e: Exception) {
                    super.onPermissionRequest(request)
                }
            }

            // Rock-solid file chooser for PDF, Excel, Images and documents
            override fun onShowFileChooser(
                mWebView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                try {
                    val acceptTypes = fileChooserParams?.acceptTypes?.filter { it.isNotBlank() } ?: emptyList()
                    val mimeTypes = mutableListOf<String>()

                    for (raw in acceptTypes) {
                        for (type in raw.split(",")) {
                            val trimmed = type.trim()
                            when {
                                trimmed.contains("/") -> mimeTypes.add(trimmed)
                                trimmed.equals(".pdf", ignoreCase = true) -> mimeTypes.add("application/pdf")
                                trimmed.equals(".xlsx", ignoreCase = true) -> mimeTypes.add("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                                trimmed.equals(".xls", ignoreCase = true) -> mimeTypes.add("application/vnd.ms-excel")
                                trimmed.equals(".csv", ignoreCase = true) -> mimeTypes.add("text/csv")
                                trimmed.equals(".png", ignoreCase = true) -> mimeTypes.add("image/png")
                                trimmed.equals(".jpg", ignoreCase = true) || trimmed.equals(".jpeg", ignoreCase = true) -> mimeTypes.add("image/jpeg")
                                trimmed == "*/*" -> mimeTypes.add("*/*")
                            }
                        }
                    }

                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        if (mimeTypes.isEmpty()) {
                            type = "*/*"
                        } else if (mimeTypes.size == 1) {
                            type = mimeTypes[0]
                        } else {
                            type = "*/*"
                            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
                        }
                        if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                    }

                    val chooser = Intent.createChooser(intent, "फ़ाइल चुनें (Select File)")
                    filePickerLauncher.launch(chooser)
                    return true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    Toast.makeText(this@MainActivity, "फ़ाइल पिकर खोलने में समस्या आई", Toast.LENGTH_SHORT).show()
                    return false
                }
            }
        }
    }

    // Injects JavaScript to intercept all blob: and data: download triggers in DOM
    private fun injectBlobInterceptionScript() {
        val script = """
            (function() {
                if (window.__vijaySetuDownloaderActive) return;
                window.__vijaySetuDownloaderActive = true;

                function fetchAndSendBlob(blobUrl, filename) {
                    try {
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', blobUrl, true);
                        xhr.responseType = 'blob';
                        xhr.onload = function() {
                            if (this.status === 200 || this.status === 0) {
                                var reader = new FileReader();
                                reader.onloadend = function() {
                                    if (window.AndroidBlobDownloader && window.AndroidBlobDownloader.processBase64Blob) {
                                        window.AndroidBlobDownloader.processBase64Blob(reader.result, filename || 'download.xlsx');
                                    }
                                };
                                reader.readAsDataURL(this.response);
                            }
                        };
                        xhr.onerror = function() {
                            console.error('Blob fetch failed for', blobUrl);
                        };
                        xhr.send();
                    } catch (e) {
                        console.error('fetchAndSendBlob error', e);
                    }
                }

                // Intercept synthetic/programmatic <a>.click() calls
                var origClick = HTMLAnchorElement.prototype.click;
                HTMLAnchorElement.prototype.click = function() {
                    var href = this.getAttribute('href') || this.href || '';
                    var downloadAttr = this.getAttribute('download') || this.download || '';
                    if (href) {
                        if (href.indexOf('data:') === 0) {
                            if (window.AndroidBlobDownloader && window.AndroidBlobDownloader.processBase64Blob) {
                                window.AndroidBlobDownloader.processBase64Blob(href, downloadAttr || 'download.jpg');
                                return;
                            }
                        } else if (href.indexOf('blob:') === 0) {
                            fetchAndSendBlob(href, downloadAttr || 'download.xlsx');
                            return;
                        }
                    }
                    return origClick.apply(this, arguments);
                };

                // Intercept user tap/click on download elements
                document.addEventListener('click', function(e) {
                    var target = e.target;
                    while (target && target.tagName !== 'A') {
                        target = target.parentElement;
                    }
                    if (target && target.tagName === 'A') {
                        var href = target.getAttribute('href') || target.href || '';
                        var downloadAttr = target.getAttribute('download') || target.download || '';
                        if (href) {
                            if (href.indexOf('data:') === 0) {
                                e.preventDefault();
                                e.stopPropagation();
                                if (window.AndroidBlobDownloader && window.AndroidBlobDownloader.processBase64Blob) {
                                    window.AndroidBlobDownloader.processBase64Blob(href, downloadAttr || 'download.jpg');
                                }
                            } else if (href.indexOf('blob:') === 0) {
                                e.preventDefault();
                                e.stopPropagation();
                                fetchAndSendBlob(href, downloadAttr || 'download.xlsx');
                            }
                        }
                    }
                }, true);
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun handleUrlNavigation(url: String): Boolean {
        // Direct download interception for export links
        val lowerUrl = url.lowercase()
        if (lowerUrl.endsWith(".pdf") || lowerUrl.endsWith(".xlsx") || lowerUrl.endsWith(".xls") || lowerUrl.endsWith(".csv") || lowerUrl.contains("/download-excel/")) {
            downloadHttpFile(url)
            return true
        }

        // Handle custom intent URIs (WhatsApp, UPI, custom apps)
        if (url.startsWith("intent:")) {
            return try {
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                if (intent != null) {
                    val info = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    if (info != null) {
                        startActivity(intent)
                        return true
                    }
                    val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                    if (fallbackUrl != null) {
                        webView.loadUrl(fallbackUrl)
                        return true
                    }
                }
                false
            } catch (e: Exception) {
                false
            }
        }

        // Direct WhatsApp Links
        if (url.startsWith("whatsapp://") || url.startsWith("https://wa.me/") || url.startsWith("https://api.whatsapp.com/")) {
            return try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
                true
            } catch (e: Exception) {
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(browserIntent)
                    true
                } catch (ex: Exception) {
                    false
                }
            }
        }

        // Phone call, Email, Maps
        if (url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("geo:")) {
            return try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
                true
            } catch (e: Exception) {
                false
            }
        }

        return false
    }

    private fun downloadHttpFile(
        url: String,
        userAgent: String = webView.settings.userAgentString,
        contentDisposition: String? = null,
        mimetype: String? = null
    ) {
        try {
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                // Handled via JS bridge
                return
            }

            val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                if (mimetype != null) setMimeType(mimetype)
                addRequestHeader("User-Agent", userAgent)
                val cookie = CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrBlank()) {
                    addRequestHeader("Cookie", cookie)
                }
                setDescription("VijaySetu File Download")
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "डाउनलोड शुरू हो गया...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            } catch (ex: Exception) {
                Toast.makeText(this, "डाउनलोड पूरा नहीं हो सका", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupSwipeRefresh() {
        swipeRefreshLayout.setColorSchemeResources(R.color.primary_emerald)
        swipeRefreshLayout.setOnRefreshListener {
            if (isNetworkAvailable()) {
                webView.reload()
            } else {
                swipeRefreshLayout.isRefreshing = false
                showOfflineView()
            }
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    private fun showOfflineView() {
        webView.visibility = View.GONE
        progressBar.visibility = View.GONE
        offlineLayout.visibility = View.VISIBLE
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
            return activeNetwork.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
            return networkInfo.isConnected
        }
    }

    fun getMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".xlsx", ignoreCase = true) -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            fileName.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
            fileName.endsWith(".csv", ignoreCase = true) -> "text/csv"
            fileName.endsWith(".png", ignoreCase = true) -> "image/png"
            fileName.endsWith(".jpg", ignoreCase = true) || fileName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            else -> "*/*"
        }
    }

    class BlobDownloaderInterface(private val activity: MainActivity) {
        @JavascriptInterface
        fun processBase64Blob(base64Data: String, suggestedName: String) {
            activity.runOnUiThread {
                try {
                    val cleanBase64 = if (base64Data.contains(",")) {
                        base64Data.substringAfter(",")
                    } else {
                        base64Data
                    }
                    val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                    val rawName = if (suggestedName.isNotBlank() && suggestedName != "undefined") {
                        suggestedName
                    } else {
                        "vijaysetu_${System.currentTimeMillis()}.xlsx"
                    }
                    val fileName = rawName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                    val mimeType = activity.getMimeType(fileName)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        if (uri != null) {
                            activity.contentResolver.openOutputStream(uri)?.use { os ->
                                os.write(bytes)
                            }
                            Toast.makeText(activity, "फ़ाइल डाउनलोड हो गई: $fileName", Toast.LENGTH_LONG).show()
                        } else {
                            throw Exception("MediaStore insert returned null")
                        }
                    } else {
                        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        if (!downloadsDir.exists()) downloadsDir.mkdirs()
                        val file = File(downloadsDir, fileName)
                        FileOutputStream(file).use { os ->
                            os.write(bytes)
                        }
                        MediaScannerConnection.scanFile(activity, arrayOf(file.absolutePath), arrayOf(mimeType), null)
                        Toast.makeText(activity, "फ़ाइल डाउनलोड हो गई: $fileName", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(activity, "डाउनलोड पूरा नहीं हो सका", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
