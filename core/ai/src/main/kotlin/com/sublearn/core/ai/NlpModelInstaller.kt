package com.sublearn.core.ai

import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import java.io.File

/**
 * Progress of one model's install, across all of its files.
 *
 * @property modelId the catalog id being installed
 * @property fileIndex zero-based index of the asset being fetched
 * @property fileCount how many assets the model needs
 * @property fileName the asset being fetched, which the UI shows for a multi-file model
 * @property bytesDownloaded bytes of the *whole* model downloaded so far
 * @property totalBytes the model's published size summed over every asset
 */
data class NlpModelProgress(
    val modelId: String,
    val fileIndex: Int,
    val fileCount: Int,
    val fileName: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes > 0L) {
            (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * Installs and removes the optional NLP models (`D-74`).
 *
 * Two things make this more than a loop over [ModelDownloader]. An encoder needs its graph *and*
 * its vocabulary, so a model is only installed once every asset is present; and a model is only
 * reported as installed when the files pass [ModelStore.isInstalled], so a hand-copied directory
 * counts and a half-finished download does not.
 *
 * The installer is deliberately not a service with its own thread: callers run it inside their own
 * coroutine and get progress through [install]'s callback, which keeps cancellation the caller's
 * business.
 *
 * @property store where the models live
 * @property downloader the one download path shared with packs
 */
class NlpModelInstaller(
    private val store: ModelStore,
    /**
     * One file per call, reporting progress as it arrives. Defaults to the real [ModelDownloader];
     * a test passes a function that writes a file, which is what keeps this class testable without a
     * network or a device.
     */
    private val downloader: (DownloadPlan, (DownloadProgress) -> Unit) -> AppResult<DownloadOutcome> =
        ModelDownloader()::download,
) {

    /** Every model the catalog knows, in catalog order. */
    fun availableModels(): List<NlpModel> = NlpModelCatalog.all

    /** Catalog ids fully present on disk. */
    fun installedIds(): Set<String> = store.installedIds()

    fun isInstalled(modelId: String): Boolean = store.isInstalled(modelId)

    /** The model's layout, or null when this build cannot install it. */
    fun layoutFor(modelId: String): ModelFileLayout? = store.layoutFor(modelId)

    /** Bytes on disk after install, which is what the delete confirmation quotes. */
    fun installedBytes(modelId: String): Long {
        val layout = store.layoutFor(modelId) ?: return 0L
        return layout.requiredFiles.sumOf { name ->
            val file = layout.fileUnder(store.root, name)
            if (file.isFile) file.length() else 0L
        }
    }

    /**
     * Downloads every file the model needs.
     *
     * Files are fetched in layout order, so the large graph or `.task` bundle comes last and a
     * dropped connection costs the user the smallest possible re-download. On any failure the
     * partial directory is removed, because a model that is half-present must never be offered as
     * ready: `ModelStore.isInstalled` would report it missing anyway, but leaving 500 MB of a
     * failed SMART download on the device is its own bug.
     */
    fun install(
        modelId: String,
        onProgress: (NlpModelProgress) -> Unit = {},
    ): AppResult<ModelFileLayout> {
        val layout = store.layoutFor(modelId)
            ?: return failure("this build has no file layout for model $modelId")
        if (isInstalled(modelId)) return AppResult.Success(layout)
        if (layout.requiresTermsAcceptance) {
            return failure(
                "${layout.id} is published under a licence you have to accept first; " +
                    "visit ${layout.assets.firstOrNull()?.url ?: "huggingface.co"} and accept it",
            )
        }

        val directory = layout.directoryUnder(store.root)
        directory.mkdirs()
        val total = layout.downloadBytes

        try {
            return installAssets(layout, directory, total, onProgress)
        } catch (aborted: Throwable) {
            // The caller stopped the install from inside the progress callback (the Downloads
            // screen's stop button cancels the coroutine, and `ensureActive` throws here). A model
            // that is half-present must never be offered as ready, so the directory goes the same
            // way it goes on a failed download.
            directory.deleteRecursively()
            throw aborted
        }
    }

    private fun installAssets(
        layout: ModelFileLayout,
        directory: File,
        total: Long,
        onProgress: (NlpModelProgress) -> Unit,
    ): AppResult<ModelFileLayout> {
        val modelId = layout.id
        var completedBytes = 0L
        for ((index, asset) in layout.assets.withIndex()) {
            val target = File(directory, asset.name)
            // A file that is already the right size is left alone, so re-running an interrupted
            // install does not re-download the 500 MB that did arrive.
            if (target.isFile && target.length() == asset.sizeBytes) {
                completedBytes += asset.sizeBytes
                onProgress(progressOf(layout, index, asset.name, completedBytes, total))
                continue
            }

            val plan = DownloadPlan(
                urls = listOf(asset.url),
                expectedSha256 = asset.sha256.orEmpty(),
                targetDirectory = directory,
                stagingFileName = "${asset.name}.part",
            )

            when (
                val result = downloader(plan) { progress ->
                    onProgress(
                        progressOf(layout, index, asset.name, completedBytes + progress.bytesDownloaded, total),
                    )
                }
            ) {
                is AppResult.Success -> {
                    val staged = File(directory, plan.stagingFileName)
                    if (!staged.renameTo(target)) {
                        // A rename that fails is a full or locked filesystem, not a network
                        // problem; the copy keeps the install working on those devices.
                        staged.copyTo(target, overwrite = true)
                        staged.delete()
                    }
                    completedBytes += asset.sizeBytes
                    onProgress(progressOf(layout, index, asset.name, completedBytes, total))
                }

                is AppResult.Failure -> {
                    directory.deleteRecursively()
                    return result
                }
            }
        }

        if (!isInstalled(modelId)) {
            directory.deleteRecursively()
            return failure("${layout.id} did not pass its post-install check; the files were removed")
        }
        return AppResult.Success(layout)
    }

    /** Removes every file of a model. */
    fun delete(modelId: String): Boolean = store.delete(modelId)

    private fun progressOf(
        layout: ModelFileLayout,
        index: Int,
        fileName: String,
        downloaded: Long,
        total: Long,
    ): NlpModelProgress = NlpModelProgress(
        modelId = layout.id,
        fileIndex = index,
        fileCount = layout.assets.size,
        fileName = fileName,
        bytesDownloaded = downloaded,
        totalBytes = total,
    )

    private fun failure(message: String): AppResult.Failure = AppResult.Failure(
        SubLearnError(SubLearnError.Kind.MalformedInput, message),
    )
}
