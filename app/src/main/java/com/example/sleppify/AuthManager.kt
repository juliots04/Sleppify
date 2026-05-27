package com.example.sleppify

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.text.TextUtils
import androidx.core.content.ContextCompat
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider

class AuthManager private constructor(context: Context) {

    interface AuthCallback {
        fun onSuccess(user: FirebaseUser)
        fun onError(message: String)
    }

    fun interface SimpleCallback {
        fun onComplete(success: Boolean, message: String?)
    }

    private interface AuthCredentialCallback {
        fun onSuccess(credential: AuthCredential)
        fun onError(message: String)
    }

    private val appContext: Context = context.applicationContext
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()

    fun isSignedIn(): Boolean {
        return firebaseAuth.currentUser != null
    }

    fun getCurrentUser(): FirebaseUser? {
        return firebaseAuth.currentUser
    }

    fun getUserId(): String? {
        return firebaseAuth.currentUser?.uid
    }

    fun getDisplayName(): String {
        val user = firebaseAuth.currentUser
        if (user == null || user.displayName.isNullOrBlank()) {
            return "Invitado"
        }
        return user.displayName ?: "Invitado"
    }

    fun getEmail(): String {
        val user = firebaseAuth.currentUser
        if (user == null || user.email.isNullOrBlank()) {
            return "Sin correo"
        }
        return user.email ?: "Sin correo"
    }

    fun getPhotoUrl(): Uri? {
        return firebaseAuth.currentUser?.photoUrl
    }

    fun signIn(activity: Activity, callback: AuthCallback) {
        val alreadySigned = firebaseAuth.currentUser
        if (alreadySigned != null) {
            callback.onSuccess(alreadySigned)
            return
        }

        val serverClientId = try {
            val resId = activity.resources.getIdentifier(
                "default_web_client_id",
                "string",
                activity.packageName
            )
            if (resId == 0) "" else activity.getString(resId)
        } catch (_: Exception) {
            callback.onError("No se encontro default_web_client_id. Verifica google-services.json.")
            return
        }

        if (TextUtils.isEmpty(serverClientId)) {
            callback.onError("default_web_client_id esta vacio. Revisa Firebase y google-services.json.")
            return
        }

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(serverClientId)
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val cm = CredentialManager.create(activity)
        cm.getCredentialAsync(
            activity,
            request,
            null,
            ContextCompat.getMainExecutor(activity),
            object : androidx.credentials.CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    handleCredentialResponse(activity, result, callback)
                }

                override fun onError(e: GetCredentialException) {
                    callback.onError("No fue posible iniciar sesion con Google: " + e.message)
                }
            }
        )
    }

    private fun handleCredentialResponse(
        activity: Activity,
        response: GetCredentialResponse,
        callback: AuthCallback
    ) {
        val credential: Credential = response.credential
        if (credential !is CustomCredential) {
            callback.onError("No se recibio una credencial de Google valida.")
            return
        }

        if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            callback.onError("Tipo de credencial no soportado para Google Sign-In.")
            return
        }

        val googleIdTokenCredential = try {
            GoogleIdTokenCredential.createFrom(credential.data)
        } catch (_: Exception) {
            callback.onError("No se pudo leer el token de Google.")
            return
        }

        val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)

        firebaseAuth.signInWithCredential(firebaseCredential)
            .addOnCompleteListener(activity) { task ->
                if (!task.isSuccessful) {
                    val reason = task.exception?.message ?: "error desconocido"
                    callback.onError("Firebase rechazo el inicio de sesion: $reason")
                    return@addOnCompleteListener
                }

                val signedUser = firebaseAuth.currentUser
                if (signedUser == null) {
                    callback.onError("Firebase no devolvio un usuario activo.")
                    return@addOnCompleteListener
                }

                // Cache profile photo URL immediately so header UI can pick it up
                // without waiting for PlaylistDetailFragment to open.
                signedUser.photoUrl?.let { photoUri ->
                    activity.getSharedPreferences("streaming_cache", Activity.MODE_PRIVATE)
                        .edit()
                        .putString("cached_google_profile_photo_url", photoUri.toString())
                        .apply()
                }

                callback.onSuccess(signedUser)
            }
    }

    fun signOut(context: Context, callback: SimpleCallback) {
        firebaseAuth.signOut()
        clearCredentialState(context, callback)
    }

    fun deleteCurrentUser(callback: SimpleCallback) {
        val user = firebaseAuth.currentUser
        if (user == null) {
            callback.onComplete(false, "No hay sesion activa para eliminar.")
            return
        }

        user.delete()
            .addOnSuccessListener {
                firebaseAuth.signOut()
                clearCredentialState(appContext, callback)
            }
            .addOnFailureListener { e ->
                if (e is FirebaseAuthRecentLoginRequiredException) {
                    callback.onComplete(false, "Por seguridad, vuelve a iniciar sesion y repite la eliminacion.")
                    return@addOnFailureListener
                }

                val message = if (e.message.isNullOrBlank()) "No se pudo eliminar la cuenta." else e.message
                callback.onComplete(false, message)
            }
    }

    fun deleteCurrentUser(activity: Activity, callback: SimpleCallback) {
        val user = firebaseAuth.currentUser
        if (user == null) {
            callback.onComplete(false, "No hay sesion activa para eliminar.")
            return
        }

        deleteCurrentUserInternal(activity, user, false, callback)
    }

    private fun deleteCurrentUserInternal(
        activity: Activity,
        user: FirebaseUser,
        alreadyRetriedAfterReauth: Boolean,
        callback: SimpleCallback
    ) {
        user.delete()
            .addOnSuccessListener {
                firebaseAuth.signOut()
                clearCredentialState(activity, callback)
            }
            .addOnFailureListener { e ->
                if (e is FirebaseAuthRecentLoginRequiredException && !alreadyRetriedAfterReauth) {
                    reauthenticateForSensitiveAction(activity, object : SimpleCallback {
                        override fun onComplete(success: Boolean, message: String?) {
                            if (!success) {
                                callback.onComplete(false, message)
                                return
                            }

                            val refreshedUser = firebaseAuth.currentUser
                            if (refreshedUser == null) {
                                callback.onComplete(false, "No se pudo recuperar la sesion para eliminar la cuenta.")
                                return
                            }

                            deleteCurrentUserInternal(activity, refreshedUser, true, callback)
                        }
                    })
                    return@addOnFailureListener
                }

                val message = if (e.message.isNullOrBlank()) "No se pudo eliminar la cuenta." else e.message
                callback.onComplete(false, message)
            }
    }

    private fun reauthenticateForSensitiveAction(activity: Activity, callback: SimpleCallback) {
        requestGoogleCredentialForReauth(activity, true, object : AuthCredentialCallback {
            override fun onSuccess(credential: AuthCredential) {
                val currentUser = firebaseAuth.currentUser
                if (currentUser == null) {
                    callback.onComplete(false, "No hay sesion activa para reautenticar.")
                    return
                }

                currentUser.reauthenticate(credential)
                    .addOnSuccessListener {
                        callback.onComplete(true, null)
                    }
                    .addOnFailureListener { e ->
                        val message = if (e.message.isNullOrBlank()) {
                            "No se pudo reautenticar la cuenta."
                        } else {
                            e.message
                        }
                        callback.onComplete(false, message)
                    }
            }

            override fun onError(message: String) {
                callback.onComplete(false, message)
            }
        })
    }

    private fun requestGoogleCredentialForReauth(
        activity: Activity,
        authorizedOnly: Boolean,
        callback: AuthCredentialCallback
    ) {
        val serverClientId = try {
            val resId = activity.resources.getIdentifier(
                "default_web_client_id",
                "string",
                activity.packageName
            )
            if (resId == 0) "" else activity.getString(resId)
        } catch (_: Exception) {
            callback.onError("No se encontro default_web_client_id. Verifica Firebase.")
            return
        }

        if (TextUtils.isEmpty(serverClientId)) {
            callback.onError("default_web_client_id esta vacio. Revisa Firebase y google-services.json.")
            return
        }

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(authorizedOnly)
            .setServerClientId(serverClientId)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val cm = CredentialManager.create(activity)
        cm.getCredentialAsync(
            activity,
            request,
            null,
            ContextCompat.getMainExecutor(activity),
            object : androidx.credentials.CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val credential = extractGoogleAuthCredential(result)
                    if (credential == null) {
                        callback.onError("No se pudo leer la credencial de Google para confirmar eliminacion.")
                        return
                    }
                    callback.onSuccess(credential)
                }

                override fun onError(e: GetCredentialException) {
                    if (authorizedOnly) {
                        // Fallback: abre selector de cuenta si no hay autorizada disponible.
                        requestGoogleCredentialForReauth(activity, false, callback)
                        return
                    }
                    callback.onError("No fue posible validar la sesion para eliminar la cuenta: " + e.message)
                }
            }
        )
    }

    private fun extractGoogleAuthCredential(response: GetCredentialResponse): AuthCredential? {
        val credential = response.credential
        if (credential !is CustomCredential) {
            return null
        }

        if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return null
        }

        val googleIdTokenCredential = try {
            GoogleIdTokenCredential.createFrom(credential.data)
        } catch (_: Exception) {
            return null
        }

        return GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
    }

    private fun clearCredentialState(context: Context, callback: SimpleCallback) {
        try {
            val cm = CredentialManager.create(context)
            cm.clearCredentialStateAsync(
                ClearCredentialStateRequest(),
                null,
                ContextCompat.getMainExecutor(context),
                object : androidx.credentials.CredentialManagerCallback<Void?, ClearCredentialException> {
                    override fun onResult(result: Void?) {
                        callback.onComplete(true, null)
                    }

                    override fun onError(e: ClearCredentialException) {
                        callback.onComplete(true, null)
                    }
                }
            )
        } catch (_: Throwable) {
            callback.onComplete(true, null)
        }
    }

    companion object {
        @Volatile
        private var instance: AuthManager? = null

        @JvmStatic
        fun getInstance(context: Context): AuthManager {
            val current = instance
            if (current != null) {
                return current
            }

            return synchronized(AuthManager::class.java) {
                val again = instance
                if (again != null) {
                    again
                } else {
                    val created = AuthManager(context)
                    instance = created
                    created
                }
            }
        }
    }
}
