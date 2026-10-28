package mihon.feature.drivesync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.app.di.appGraph
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Copias de seguridad en Google Drive, en la carpeta oculta de la app (appDataFolder).
 *
 * La autorización usa la cuenta de Google del móvil; Google identifica la app por su nombre de
 * paquete y la huella de la clave de firma, así que no hace falta guardar ningún secreto en el código.
 */
object GoogleDriveSync {

    private const val SCOPE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    private const val API = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD_API = "https://www.googleapis.com/upload/drive/v3/files"
    private const val MAX_REMOTE_BACKUPS = 10

    data class RemoteBackup(val id: String, val name: String, val createdTime: String, val size: Long)

    sealed interface AuthState {
        data class Authorized(val token: String) : AuthState
        data class NeedsConsent(val pendingIntent: PendingIntent) : AuthState
    }

    private fun request() = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE_APPDATA)))
        .build()

    /** Pide autorización. Si el usuario aún no ha aceptado, devuelve el intent para mostrar el consentimiento. */
    suspend fun authorize(context: Context): AuthState {
        val result = Identity.getAuthorizationClient(context).authorize(request()).await()
        return result.toState()
    }

    /** Lee el resultado de la pantalla de consentimiento de Google. */
    fun resultFromIntent(context: Context, data: Intent?): AuthState? {
        return runCatching {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).toState()
        }.getOrNull()
    }

    private fun AuthorizationResult.toState(): AuthState {
        val intent = pendingIntent
        return if (hasResolution() && intent != null) {
            AuthState.NeedsConsent(intent)
        } else {
            AuthState.Authorized(accessToken ?: error("Google no devolvió un token de acceso"))
        }
    }

    /** Token sin interfaz (para tareas en segundo plano). Null si hace falta volver a autorizar. */
    private suspend fun silentToken(context: Context): String? =
        (authorize(context) as? AuthState.Authorized)?.token

    /** Crea una copia local completa y la sube a Drive. */
    suspend fun backupNow(context: Context): String = withContext(Dispatchers.IO) {
        val token = silentToken(context) ?: error("Vuelve a conectar Google Drive en Ajustes")
        val dir = File(context.cacheDir, "drive").apply { mkdirs() }
        val file = File(dir, fileName()).apply { delete(); createNewFile() }
        context.appGraph.backupCreatorFactory.create(false).backup(Uri.fromFile(file), BackupOptions())
        upload(context, token, file.name, file.asRequestBody(BACKUP_TYPE))
        file.delete()
        cleanup(context, token)
        file.name
    }

    /** Sube un archivo de copia ya creado (p. ej. una copia automática). */
    suspend fun uploadExisting(context: Context, uri: Uri, name: String) = withContext(Dispatchers.IO) {
        val token = silentToken(context) ?: return@withContext
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext
        upload(context, token, name, bytes.toRequestBody(BACKUP_TYPE))
        cleanup(context, token)
    }

    suspend fun list(context: Context): List<RemoteBackup> = withContext(Dispatchers.IO) {
        val token = silentToken(context) ?: error("Vuelve a conectar Google Drive en Ajustes")
        listWithToken(context, token)
    }

    /** Descarga una copia de Drive a un archivo temporal y devuelve su Uri para restaurarla. */
    suspend fun download(context: Context, backup: RemoteBackup): Uri = withContext(Dispatchers.IO) {
        val token = silentToken(context) ?: error("Vuelve a conectar Google Drive en Ajustes")
        val request = Request.Builder()
            .url("$API/${backup.id}?alt=media")
            .header("Authorization", "Bearer $token")
            .build()
        val file = File(File(context.cacheDir, "drive").apply { mkdirs() }, backup.name)
        context.appGraph.networkHelper.client.newCall(request).awaitSuccess().use { response ->
            file.outputStream().use { out -> response.body.byteStream().copyTo(out) }
        }
        Uri.fromFile(file)
    }

    private suspend fun listWithToken(context: Context, token: String): List<RemoteBackup> {
        val request = Request.Builder()
            .url("$API?spaces=appDataFolder&orderBy=createdTime%20desc&pageSize=100&fields=files(id,name,createdTime,size)")
            .header("Authorization", "Bearer $token")
            .build()
        val text = context.appGraph.networkHelper.client.newCall(request).awaitSuccess().use { it.body.string() }
        val files = (Json.parseToJsonElement(text) as? JsonObject)?.get("files") as? JsonArray ?: return emptyList()
        return files.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            RemoteBackup(
                id = obj.string("id") ?: return@mapNotNull null,
                name = obj.string("name") ?: "backup",
                createdTime = obj.string("createdTime").orEmpty(),
                size = obj.string("size")?.toLongOrNull() ?: 0L,
            )
        }
    }

    private suspend fun upload(context: Context, token: String, name: String, body: okhttp3.RequestBody) {
        val metadata = buildJsonObject {
            put("name", name)
            put("parents", buildJsonArray { add(JsonPrimitive("appDataFolder")) })
        }.toString()
        val multipart = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(body)
            .build()
        val request = Request.Builder()
            .url("$UPLOAD_API?uploadType=multipart")
            .header("Authorization", "Bearer $token")
            .post(multipart)
            .build()
        context.appGraph.networkHelper.client.newCall(request).awaitSuccess().close()
    }

    /** Deja solo las copias más recientes en Drive. */
    private suspend fun cleanup(context: Context, token: String) {
        listWithToken(context, token).drop(MAX_REMOTE_BACKUPS).forEach { old ->
            val request = Request.Builder()
                .url("$API/${old.id}")
                .header("Authorization", "Bearer $token")
                .delete()
                .build()
            runCatching { context.appGraph.networkHelper.client.newCall(request).awaitSuccess().close() }
        }
    }

    private fun fileName(): String {
        val date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
        return "mashiro_$date.tachibk"
    }

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.content

    private val BACKUP_TYPE = "application/octet-stream".toMediaType()

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
