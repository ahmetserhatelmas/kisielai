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

object MediaProjectionStore {

    private var manager: MediaProjectionManager? = null
    private var projection: MediaProjection? = null
    private var pending: CancellableContinuation<MediaProjection>? = null

    private var savedResultCode: Int? = null
    private var savedResultData: Intent? = null

    fun init(context: Context) {
        if (manager == null) {
            manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        }
    }

    fun createCaptureIntent(): Intent = manager!!.createScreenCaptureIntent()

    fun onPermissionResult(resultCode: Int, data: Intent?) {
        ScreenCapturePipeline.permissionRequestInFlight = false
        if (resultCode == Activity.RESULT_OK && data != null) {
            savedResultCode = resultCode
            savedResultData = Intent(data)
            rebuildProjection()
            pending?.resume(projection!!)
        } else {
            pending?.resumeWithException(Exception("Ekran kaydı izni verilmedi."))
        }
        pending = null
    }

    fun hasSavedPermission(): Boolean = savedResultCode == Activity.RESULT_OK && savedResultData != null

    fun ensureProjection(): MediaProjection? {
        if (projection != null) return projection
        rebuildProjection()
        return projection
    }

    private fun rebuildProjection() {
        val code = savedResultCode ?: return
        val data = savedResultData ?: return
        projection?.stop()
        projection = manager!!.getMediaProjection(code, data)
    }

    suspend fun awaitProjection(requestPermission: () -> Unit): MediaProjection {
        ensureProjection()?.let { return it }
        return suspendCancellableCoroutine { cont ->
            pending?.cancel()
            pending = cont
            ScreenCapturePipeline.permissionRequestInFlight = true
            requestPermission()
            cont.invokeOnCancellation {
                pending = null
                ScreenCapturePipeline.permissionRequestInFlight = false
            }
        }
    }

    fun currentProjection(): MediaProjection? = projection

    fun release() {
        pending?.cancel()
        pending = null
        projection?.stop()
        projection = null
        savedResultCode = null
        savedResultData = null
    }
}
