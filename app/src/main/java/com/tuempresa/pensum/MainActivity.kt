package com.tuempresa.pensum

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var downloadId: Long = -1

    // 🔗 URL del version.json en tu repositorio (raw)
    // ⚠️ Cambia TuUsuario/TuRepo por los tuyos
    private val versionJsonUrl =
        "https://raw.githubusercontent.com/TuUsuario/TuRepo/main/version.json"

    private val apkFileName = "pensum_update.apk"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
        }

        webView.webViewClient = WebViewClient()

        // Puente JavaScript -> Kotlin
        webView.addJavascriptInterface(WebAppInterface(), "Android")

        webView.loadUrl("file:///android_asset/www/index.html")
    }

    // ============================================================
    //  Interfaz expuesta al JavaScript
    // ============================================================
    inner class WebAppInterface {
        @JavascriptInterface
        fun checkForUpdates() {
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    "Buscando actualizaciones...",
                    Toast.LENGTH_SHORT
                ).show()
            }
            fetchVersionInfo()
        }

        @JavascriptInterface
        fun getAppVersion(): String = BuildConfig.VERSION_NAME
    }

    // ============================================================
    //  1) Consultar version.json desde GitHub
    // ============================================================
    private fun fetchVersionInfo() {
        thread {
            try {
                val json = URL(versionJsonUrl).readText()
                val obj = JSONObject(json)
                val remoteVersionCode = obj.getInt("versionCode")
                val remoteVersionName = obj.getString("versionName")
                val apkUrl = obj.getString("apkUrl")
                val whatsNew = obj.optString("whatsNew", "")
                val forceUpdate = obj.optBoolean("forceUpdate", false)

                val currentVersionCode = BuildConfig.VERSION_CODE

                runOnUiThread {
                    if (remoteVersionCode > currentVersionCode) {
                        showUpdateDialog(remoteVersionName, whatsNew, apkUrl, forceUpdate)
                    } else {
                        Toast.makeText(
                            this,
                            "Ya tienes la última versión ($remoteVersionName)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Error al verificar: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ============================================================
    //  2) Diálogo de actualización
    // ============================================================
    private fun showUpdateDialog(
        versionName: String,
        whatsNew: String,
        apkUrl: String,
        forceUpdate: Boolean
    ) {
        val builder = AlertDialog.Builder(this)
            .setTitle("Actualización disponible - $versionName")
            .setMessage(whatsNew.ifBlank { "Hay una nueva versión disponible." })
            .setPositiveButton("Descargar e instalar") { _, _ -> downloadApk(apkUrl) }

        if (!forceUpdate) builder.setNegativeButton("Más tarde", null)
        else builder.setCancelable(false)

        builder.show()
    }

    // ============================================================
    //  3) Descargar el APK con DownloadManager
    // ============================================================
    private fun downloadApk(apkUrl: String) {
        if (downloadId != -1L) {
            Toast.makeText(this, "Ya hay una descarga en curso", Toast.LENGTH_SHORT).show()
            return
        }

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Actualizando Pensum")
            .setDescription("Descargando nueva versión...")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setDestinationInExternalFilesDir(
                this,
                Environment.DIRECTORY_DOWNLOADS,
                apkFileName
            )
            .setMimeType("application/vnd.android.package-archive")

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = dm.enqueue(request)

        Toast.makeText(this, "Descargando actualización...", Toast.LENGTH_LONG).show()

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    installApk()
                    unregisterReceiver(this)
                    downloadId = -1L
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                onComplete,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(
                onComplete,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    // ============================================================
    //  4) Instalar el APK con FileProvider + Intent
    // ============================================================
    private fun installApk() {
        val apkFile = File(
            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            apkFileName
        )

        if (!apkFile.exists()) {
            Toast.makeText(this, "APK no encontrado", Toast.LENGTH_LONG).show()
            return
        }

        val apkUri: Uri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "No se pudo abrir el instalador: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}
