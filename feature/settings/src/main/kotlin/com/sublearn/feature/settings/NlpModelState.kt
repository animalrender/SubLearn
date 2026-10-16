package com.sublearn.feature.settings

import com.sublearn.core.ai.NlpModel
import com.sublearn.core.ai.NlpModelProgress

/**
 * One row of the accuracy-model list: the catalog entry and what is on the device right now.
 *
 * The pair is kept together because the settings card needs both — the name and size come from the
 * catalog, and the state comes from the filesystem — and a `Map<NlpModel, State>` would lose the
 * catalog's ordering, which is the order the profiles build on.
 */
data class NlpModelRow(
    val model: NlpModel,
    val state: NlpModelState,
)

/** What the Models & Downloads card shows for one accuracy model. */
sealed interface NlpModelState {
    /** Not on the device yet. */
    data object Available : NlpModelState

    /** Being fetched; [progress] is null until the first byte arrives. */
    data class Downloading(val progress: NlpModelProgress?) : NlpModelState

    /** Every file the runtime needs is present; [bytesOnDisk] is what removing it would free. */
    data class Installed(val bytesOnDisk: Long) : NlpModelState

    /** The download or the file check failed. */
    data class Error(val message: String) : NlpModelState
}
