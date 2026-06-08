package com.dilara.assistant.util

/**
 * Ekran analizi işini Activity yaşam döngüsünden bağımsız taşır.
 * İzin ekranına gidip dönünce devam edilebilir.
 */
object ScreenCapturePipeline {

    data class PendingJob(
        val prompt: String,
        val userLabel: String = "👁 Ekrana bak",
    )

    @Volatile
    var pending: PendingJob? = null

    @Volatile
    private var resultCallback: ((Result<String>) -> Unit)? = null

    @Volatile
    var permissionRequestInFlight: Boolean = false

    @Volatile
    var captureInProgress: Boolean = false

    /** İzin alındıktan sonra anlık yakalama yerine kayıt başlatılacaksa true. */
    @Volatile
    var pendingRecordStart: Boolean = false

    fun start(
        prompt: String,
        userLabel: String,
        onResult: (Result<String>) -> Unit,
    ) {
        pending = PendingJob(prompt, userLabel)
        resultCallback = onResult
    }

    fun complete(result: Result<String>) {
        resultCallback?.invoke(result)
        clear()
    }

    fun clear() {
        pending = null
        resultCallback = null
        permissionRequestInFlight = false
        captureInProgress = false
        pendingRecordStart = false
    }

    fun hasPending(): Boolean = pending != null
}
