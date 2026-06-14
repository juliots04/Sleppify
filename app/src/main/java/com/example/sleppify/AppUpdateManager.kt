package com.example.sleppify

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    private const val FIRESTORE_COLLECTION = "app_config"
    private const val FIRESTORE_DOCUMENT = "update"
    private const val FIELD_VERSION_CODE = "versionCode"
    private const val FIELD_VERSION_NAME = "versionName"
    private const val FIELD_RELEASE_NOTES = "releaseNotes"
    private const val FIELD_APK_URL = "apkUrl"

    @Volatile
    private var shownThisSession = false

    @Volatile
    private var downloadInFlight = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val downloadExecutor = Executors.newSingleThreadExecutor()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .followRedirects(true)
            .build()
    }

    /**
     * Check Firestore for a newer version. If available and not yet shown this session,
     * shows the update dialog. Safe to call from main thread — Firestore call is async.
     */
    fun checkForUpdate(activity: Activity) {
        if (shownThisSession || downloadInFlight) return
        if (activity.isFinishing || activity.isDestroyed) return

        val currentVersionCode = try {
            BuildConfig.VERSION_CODE
        } catch (e: Exception) {
            Log.w(TAG, "Could not read VERSION_CODE", e)
            return
        }

        FirebaseFirestore.getInstance()
            .collection(FIRESTORE_COLLECTION)
            .document(FIRESTORE_DOCUMENT)
            .get()
            .addOnSuccessListener { doc ->
                if (activity.isFinishing || activity.isDestroyed) return@addOnSuccessListener
                if (!doc.exists()) {
                    Log.d(TAG, "No update document found")
                    return@addOnSuccessListener
                }

                val remoteVersionCode = doc.getLong(FIELD_VERSION_CODE)?.toInt() ?: return@addOnSuccessListener
                val remoteVersionName = doc.getString(FIELD_VERSION_NAME) ?: ""
                val releaseNotes = doc.getString(FIELD_RELEASE_NOTES) ?: ""
                val apkUrl = doc.getString(FIELD_APK_URL) ?: ""

                if (remoteVersionCode <= currentVersionCode) {
                    Log.d(TAG, "App is up to date (local=$currentVersionCode, remote=$remoteVersionCode)")
                    return@addOnSuccessListener
                }

                if (apkUrl.isBlank()) {
                    Log.w(TAG, "Update available but apkUrl is empty")
                    return@addOnSuccessListener
                }

                if (shownThisSession) return@addOnSuccessListener
                shownThisSession = true

                showUpdateDialog(activity, remoteVersionName, releaseNotes, apkUrl)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to check for update", e)
            }
    }

    private fun showUpdateDialog(
        activity: Activity,
        versionName: String,
        releaseNotes: String,
        apkUrl: String
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_app_update, null)
        val tvVersion = dialogView.findViewById<TextView>(R.id.tvUpdateVersion)
        val tvNotes = dialogView.findViewById<TextView>(R.id.tvUpdateNotes)
        val llInfo = dialogView.findViewById<View>(R.id.llUpdateInfo)
        val llProgress = dialogView.findViewById<View>(R.id.llUpdateProgress)
        val btnLater = dialogView.findViewById<View>(R.id.btnUpdateLater)
        val btnUpdate = dialogView.findViewById<View>(R.id.btnUpdateNow)
        val pbDownload = dialogView.findViewById<ProgressBar>(R.id.pbDownload)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tvDownloadStatus)
        val tvPercent = dialogView.findViewById<TextView>(R.id.tvDownloadPercent)

        tvVersion.text = "Versión $versionName"
        tvNotes.text = releaseNotes.ifBlank { "Mejoras y correcciones." }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Nueva versión disponible")
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnLater.setOnClickListener {
            dialog.dismiss()
        }

        btnUpdate.setOnClickListener {
            // Switch to progress mode
            llInfo.visibility = View.GONE
            llProgress.visibility = View.VISIBLE
            dialog.setCancelable(false)

            downloadAndInstall(activity, apkUrl, pbDownload, tvStatus, tvPercent, dialog)
        }

        dialog.show()
    }

    private fun downloadAndInstall(
        activity: Activity,
        apkUrl: String,
        progressBar: ProgressBar,
        tvStatus: TextView,
        tvPercent: TextView,
        dialog: androidx.appcompat.app.AlertDialog
    ) {
        if (downloadInFlight) return
        downloadInFlight = true

        // Clean old APKs
        val updatesDir = File(activity.cacheDir, "updates")
        if (updatesDir.exists()) {
            updatesDir.listFiles()?.forEach { it.delete() }
        } else {
            updatesDir.mkdirs()
        }

        val targetFile = File(updatesDir, "sleppify-update.apk")

        downloadExecutor.execute {
            try {
                val request = Request.Builder().url(apkUrl).build()
                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    mainHandler.post {
                        tvStatus.text = "Error de descarga (${response.code})"
                        tvPercent.text = ""
                        downloadInFlight = false
                    }
                    response.close()
                    return@execute
                }

                val body = response.body ?: run {
                    mainHandler.post {
                        tvStatus.text = "Error: respuesta vacía"
                        tvPercent.text = ""
                        downloadInFlight = false
                    }
                    response.close()
                    return@execute
                }

                val contentLength = body.contentLength()
                val inputStream = body.byteStream()
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L
                var lastProgressUpdate = 0L

                inputStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdate > 150 && contentLength > 0) {
                                lastProgressUpdate = now
                                val percent = ((totalBytesRead * 100) / contentLength).toInt()
                                    .coerceIn(0, 100)
                                val downloadedMb = totalBytesRead / (1024.0 * 1024.0)
                                val totalMb = contentLength / (1024.0 * 1024.0)
                                mainHandler.post {
                                    progressBar.progress = percent
                                    tvPercent.text = "$percent% — %.1f / %.1f MB".format(downloadedMb, totalMb)
                                }
                            }
                        }
                    }
                }

                mainHandler.post {
                    progressBar.progress = 100
                    tvPercent.text = "100%"
                    tvStatus.text = "Descarga completa. Instalando..."
                    downloadInFlight = false

                    if (!activity.isFinishing && !activity.isDestroyed) {
                        dialog.dismiss()
                        installApk(activity, targetFile)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                mainHandler.post {
                    tvStatus.text = "Error: ${e.message ?: "descarga fallida"}"
                    tvPercent.text = ""
                    downloadInFlight = false
                }
            }
        }
    }

    private fun installApk(activity: Activity, apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch APK installer", e)
        }
    }
}
