package com.example.sleppify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Actualizaciones autohospedadas: la app consulta el `version.json` que publica el minipanel
 * (update-server/ en alwaysdata) y, si el versionCode remoto supera al instalado, descarga el APK
 * con progreso y lanza el instalador del sistema. SIN Firebase y SIN notificaciones: al abrir la
 * app se consulta en silencio y, si hay versión nueva, salta una ventana emergente; el flujo
 * completo también vive en Configuración > Actualizar (SettingsFragment). Este object pone la red,
 * el estado de descarga re-enganchable y el install.
 */
object AppUpdateManager {

    private const val TAG = "AppUpdateManager"

    // Panel real en alwaysdata (la app busca version.json en la raíz del dominio).
    private const val UPDATE_BASE_URL = "https://sleppifyupdates21.alwaysdata.net/"
    private const val MANIFEST_URL = UPDATE_BASE_URL + "version.json"

    class UpdateInfo(
        @JvmField val versionName: String,
        @JvmField val versionCode: Int,
        @JvmField val apkUrl: String,
        @JvmField val notes: String,
        @JvmField val sizeBytes: Long,
        // Header editable de la ventana emergente (lo fija el panel; p.ej. "🔥 NUEVA VERSIÓN").
        @JvmField val dialogTitle: String,
        // true = obligatoria (sin "Más tarde", no cancelable); false = opcional (aparece "Más
        // tarde" y el diálogo se puede cerrar). Manifiestos viejos sin el campo = obligatoria.
        @JvmField val mandatory: Boolean
    )

    @Volatile
    private var downloadInFlight = false

    // Estado de la descarga retenido en el singleton (sobrevive a la recreación del fragment por
    // rotación/tema/muerte de proceso). Cualquier instancia nueva del fragment lo re-engancha con
    // reattach() para seguir mostrando el % real en vez de una UI ociosa mientras baja el APK.
    @Volatile private var inProgressUpdate: UpdateInfo? = null
    @Volatile private var lastProgress: Int = 0
    @Volatile private var progressListener: ((Int) -> Unit)? = null
    @Volatile private var installStartedListener: (() -> Unit)? = null
    @Volatile private var errorListener: ((String) -> Unit)? = null

    @JvmStatic
    fun isDownloadInFlight(): Boolean = downloadInFlight

    @JvmStatic
    fun getInProgressUpdate(): UpdateInfo? = inProgressUpdate

    @JvmStatic
    fun getLastProgress(): Int = lastProgress

    /** Re-engancha los callbacks a la instancia viva del fragment (tras recrearse). Main thread. */
    @JvmStatic
    fun reattach(onProgress: (Int) -> Unit, onInstallStarted: () -> Unit, onError: (String) -> Unit) {
        progressListener = onProgress
        installStartedListener = onInstallStarted
        errorListener = onError
    }

    /**
     * Convierte las notas del manifiesto en viñetas (blanco y negro, igual que la vista previa del
     * panel): una viñeta por línea no vacía, quitando un guion inicial con o sin espacio
     * ("- item" y "-item" quedan igual). Compartido por la sección Actualizar y el popup.
     */
    @JvmStatic
    @JvmOverloads
    fun formatNotesAsBullets(notes: String, maxLines: Int = Int.MAX_VALUE): CharSequence {
        val trimmed = notes.trim()
        if (trimmed.isEmpty()) return "Mejoras y correcciones."
        val dashPrefix = Regex("^-\\s*")
        return trimmed.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(maxLines)
            .joinToString("\n") { "•  " + it.replaceFirst(dashPrefix, "") }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    // Chequeos y descargas en pools SEPARADOS: una descarga larga no debe bloquear los chequeos.
    private val checkExecutor = Executors.newSingleThreadExecutor()
    private val downloadExecutor = Executors.newSingleThreadExecutor()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .followRedirects(true)
            .build()
    }

    private val checkClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Lee el manifiesto del hosting de forma BLOQUEANTE (llamar solo fuera del main thread —
     * lo usa el executor de checkForUpdate). Devuelve null si ya estás al día; lanza excepción
     * en errores de red/parseo.
     */
    @JvmStatic
    @Throws(Exception::class)
    fun fetchUpdateBlocking(): UpdateInfo? {
        val request = Request.Builder().url(MANIFEST_URL).header("Cache-Control", "no-cache").build()
        checkClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val json = JSONObject(response.body?.string().orEmpty())
            val remoteCode = json.optInt("versionCode", 0)
            val remoteName = json.optString("versionName", "")
            val apk = json.optString("apk", "")
            val notes = json.optString("notes", "")
            val size = json.optLong("size", 0L)
            // Header del popup: "dialogTitle" (nuevo panel) o "header" (alias), con fallback al
            // texto por defecto. Obligatoria por defecto (compat con manifiestos antiguos).
            val dialogTitle = json.optString("dialogTitle", json.optString("header", ""))
                .ifBlank { "🔥 NUEVA VERSIÓN DISPONIBLE" }
            val mandatory = json.optBoolean("mandatory", true)

            if (remoteCode <= BuildConfig.VERSION_CODE || apk.isBlank()) {
                return null // al día
            }
            val apkUrl = UpdateArtifactUrl.resolve(response.request.url.toString(), apk)
            return UpdateInfo(remoteName, remoteCode, apkUrl, notes, size, dialogTitle, mandatory)
        }
    }

    /**
     * Consulta el manifiesto del hosting. En el main thread invoca callback(update, error):
     * update == null && error == null → ya estás al día; update != null → hay versión nueva.
     */
    @JvmStatic
    fun checkForUpdate(context: Context, callback: (UpdateInfo?, String?) -> Unit) {
        checkExecutor.execute {
            try {
                postResult(callback, fetchUpdateBlocking(), null)
            } catch (e: Exception) {
                Log.w(TAG, "checkForUpdate failed", e)
                postResult(callback, null, e.message ?: "sin conexión")
            }
        }
    }

    private fun postResult(callback: (UpdateInfo?, String?) -> Unit, update: UpdateInfo?, error: String?) {
        mainHandler.post { callback(update, error) }
    }

    /**
     * Descarga el APK a cacheDir/updates/ reportando el % en el main thread y, al completar,
     * lanza el instalador del sistema. onError también llega en el main thread.
     */
    @JvmStatic
    fun downloadAndInstall(
        context: Context,
        update: UpdateInfo,
        onProgress: (Int) -> Unit,
        onInstallStarted: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (downloadInFlight) return
        downloadInFlight = true
        inProgressUpdate = update
        lastProgress = 0
        progressListener = onProgress
        installStartedListener = onInstallStarted
        errorListener = onError

        val appContext = context.applicationContext
        val updatesDir = File(appContext.cacheDir, "updates")
        val targetFile = File(updatesDir, "sleppify-update.apk")

        downloadExecutor.execute {
            try {
                if (!updatesDir.isDirectory && !updatesDir.mkdirs()) {
                    throw IllegalStateException("No se pudo preparar la carpeta de actualizaciones.")
                }
                updatesDir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
                val request = Request.Builder().url(update.apkUrl).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        finishDownloadWithError(if (response.code == 404)
                            "El APK publicado no está disponible en el servidor. Inténtalo cuando se repare la publicación."
                            else "Error de descarga (${response.code})")
                        return@execute
                    }
                    val body = response.body ?: run {
                        finishDownloadWithError("Respuesta vacía del servidor")
                        return@execute
                    }

                    // El panel publica el tamaño en el manifiesto — fallback si el server no manda length.
                    val contentLength = if (body.contentLength() > 0) body.contentLength() else update.sizeBytes
                    val buffer = ByteArray(8192)
                    var totalBytesRead = 0L
                    var lastProgressUpdate = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(targetFile).use { output ->
                            while (true) {
                                val bytesRead = input.read(buffer)
                                if (bytesRead == -1) break
                                output.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead

                                val now = System.currentTimeMillis()
                                if (contentLength > 0 && now - lastProgressUpdate > 150) {
                                    lastProgressUpdate = now
                                    val percent = ((totalBytesRead * 100) / contentLength).toInt().coerceIn(0, 99)
                                    lastProgress = percent
                                    mainHandler.post { progressListener?.invoke(percent) }
                                }
                            }
                        }
                    }
                    if (totalBytesRead == 0L
                        || (contentLength > 0 && totalBytesRead != contentLength)
                        || (update.sizeBytes > 0 && totalBytesRead != update.sizeBytes)) {
                        throw IllegalStateException("El APK se descargó incompleto. Vuelve a intentarlo.")
                    }
                }

                val archive = appContext.packageManager.getPackageArchiveInfo(targetFile.absolutePath, 0)
                    ?: throw IllegalStateException("El servidor no devolvió un APK válido.")
                val archiveCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode
                    else @Suppress("DEPRECATION") archive.versionCode.toLong()
                if (archive.packageName != appContext.packageName || archiveCode != update.versionCode.toLong()) {
                    throw IllegalStateException("El APK no coincide con la versión anunciada por el servidor.")
                }
                mainHandler.post {
                    val progress = progressListener
                    val started = installStartedListener
                    val error = errorListener
                    clearDownloadState()
                    lastProgress = 100
                    progress?.invoke(100)
                    val launched = installApk(appContext, targetFile)
                    if (launched) started?.invoke()
                    else error?.invoke("No se pudo abrir el instalador. Habilita \"instalar apps desconocidas\".")
                }
            } catch (e: Exception) {
                targetFile.delete()
                Log.e(TAG, "download failed", e)
                finishDownloadWithError(e.message ?: "descarga fallida")
            }
        }
    }

    private fun finishDownloadWithError(message: String) {
        mainHandler.post {
            val error = errorListener
            clearDownloadState()
            error?.invoke(message)
        }
    }

    private fun clearDownloadState() {
        downloadInFlight = false
        inProgressUpdate = null
        lastProgress = 0
        progressListener = null
        installStartedListener = null
        errorListener = null
    }

    /** ¿Puede la app lanzar el instalador de APKs? (permiso "instalar apps desconocidas"). */
    @JvmStatic
    fun canInstallUnknownApps(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /** Ajustes del sistema para conceder "instalar apps desconocidas" a Sleppify. */
    @JvmStatic
    fun buildUnknownSourcesIntent(context: Context): Intent {
        return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Lanza el instalador del sistema. Devuelve false si no se pudo (para dar feedback al usuario). */
    private fun installApk(context: Context, apkFile: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch APK installer", e)
            false
        }
    }
}
