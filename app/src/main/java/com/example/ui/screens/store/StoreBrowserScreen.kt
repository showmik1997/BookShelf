package com.example.ui.screens.store

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.repository.BookShelfRepository
import com.example.data.repository.ImportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun StoreBrowserScreen(
    initialUrl: String,
    storeTitle: String,
    repository: BookShelfRepository,
    onNavigateBackToLibrary: () -> Unit,
    onOpenBookInReader: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var pageTitle by remember { mutableStateOf(storeTitle) }
    var webProgress by remember { mutableFloatStateOf(0f) }
    var isLoading by remember { mutableStateOf(true) }

    var isDownloadingPdf by remember { mutableStateOf(false) }
    var downloadedBookId by remember { mutableStateOf<String?>(null) }

    fun downloadAndImportPdf(downloadUrl: String) {
        coroutineScope.launch {
            isDownloadingPdf = true
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(downloadUrl).build()

                val bytes = withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Failed to download PDF: HTTP ${response.code}")
                        response.body?.bytes() ?: throw IOException("Empty response body")
                    }
                }

                val inferredName = downloadUrl.substringAfterLast("/").substringBefore("?").ifBlank { "Store_Download.pdf" }
                val finalName = if (inferredName.endsWith(".pdf", ignoreCase = true)) inferredName else "$inferredName.pdf"

                when (val result = repository.importPdfBytes(bytes, finalName)) {
                    is ImportResult.Success -> {
                        downloadedBookId = result.book.id
                        snackbarHostState.showSnackbar("Downloaded & imported \"${result.book.title}\" into Library!")
                    }
                    is ImportResult.Duplicate -> {
                        downloadedBookId = result.existingBook.id
                        snackbarHostState.showSnackbar("Document is already in your BookShelf library.")
                    }
                    is ImportResult.Error -> {
                        snackbarHostState.showSnackbar("Download verification failed: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Download error: ${e.localizedMessage ?: "Network failed"}")
            } finally {
                isDownloadingPdf = false
            }
        }
    }

    BackHandler {
        if (webViewInstance?.canGoBack() == true) {
            webViewInstance?.goBack()
        } else {
            onNavigateBackToLibrary()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize().testTag("store_browser_screen"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = pageTitle,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = currentUrl,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBackToLibrary,
                            modifier = Modifier.testTag("browser_back_to_library")
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Return to BookShelf")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { webViewInstance?.goBack() },
                            enabled = webViewInstance?.canGoBack() == true
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Browser Back")
                        }

                        IconButton(
                            onClick = { webViewInstance?.goForward() },
                            enabled = webViewInstance?.canGoForward() == true
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Browser Forward")
                        }

                        IconButton(onClick = { webViewInstance?.reload() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                        }

                        // Open in external browser
                        IconButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))
                            context.startActivity(intent)
                        }) {
                            Icon(Icons.Filled.OpenInBrowser, contentDescription = "Open in external browser")
                        }
                    }
                )

                if (isLoading) {
                    LinearProgressIndicator(
                        progress = { webProgress },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                url?.let { currentUrl = it }
                                isLoading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                url?.let { currentUrl = it }
                                pageTitle = view?.title ?: storeTitle
                                isLoading = false
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val target = request?.url?.toString() ?: ""
                                if (target.endsWith(".pdf", ignoreCase = true)) {
                                    downloadAndImportPdf(target)
                                    return true
                                }
                                return false
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                super.onProgressChanged(view, newProgress)
                                webProgress = newProgress / 100f
                                if (newProgress == 100) isLoading = false
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                super.onReceivedTitle(view, title)
                                title?.let { pageTitle = it }
                            }
                        }

                        setDownloadListener(DownloadListener { url, _, _, mimeType, _ ->
                            if (mimeType.contains("pdf", ignoreCase = true) || url.contains(".pdf", ignoreCase = true)) {
                                downloadAndImportPdf(url)
                            } else {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                ctx.startActivity(intent)
                            }
                        })

                        loadUrl(initialUrl)
                        webViewInstance = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Download Banner Overlay
            AnimatedVisibility(
                visible = isDownloadingPdf,
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Downloading & verifying PDF into BookShelf...",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Quick open in Reader prompt if a book was downloaded
            if (downloadedBookId != null && !isDownloadingPdf) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("PDF Added to BookShelf!", fontWeight = FontWeight.Bold)
                            Text("The document is now available in your Library.", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = {
                                val id = downloadedBookId!!
                                downloadedBookId = null
                                onOpenBookInReader(id)
                            }
                        ) {
                            Text("Read Now")
                        }
                    }
                }
            }
        }
    }
}
