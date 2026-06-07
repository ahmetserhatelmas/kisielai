package com.dilara.assistant.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * MediaProjection oturumunu Activity yeniden oluşsa bile korur.
 * Ekran paylaşım izni sırasında uygulama arka plana gidince continuation kaybolmasın diye.
 */
object MediaProjectionStore {

    private var manager: MediaProjectionManager? = null
    private var projection: MediaProjection? = null
    private var pending: CancellableContinuation<MediaProjection>? = null

    fun init(context: Context) {
        if (manager == null) {
            manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        }
    }

    fun createCaptureIntent(): Intent {
        return manager!!.createScreenCaptureIntent()
    }

    fun onPermissionResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            projection?.stop()
            projection = manager!!.getMediaProjection(resultCode, data)
            pending?.resume(projection!!)
        } else {
            pending?.resumeWithException(Exception("Ekran kaydı izni verilmedi."))
        }
        pending = null
    }

    suspend fun awaitProjection(requestPermission: () -> Unit): MediaProjection {
        projection?.let { return it }
        return suspendCancellableCoroutine { cont ->
            pending?.cancel()
            pending = cont
            requestPermission()
            cont.invokeOnCancellation { pending = null }
        }
    }

    fun currentProjection(): MediaProjection? = projection

    fun release() {
        pending?.cancel()
        pending = null
        projection?.stop()
        projection = null
    }
}
