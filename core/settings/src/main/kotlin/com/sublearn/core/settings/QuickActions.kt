package com.sublearn.core.settings

import kotlinx.serialization.Serializable

/**
 * Where a control lives (SUB-2). Every quick action can sit in the dock bar, float free on the
 * video, or be hidden; the player never shows a control that has no implementation.
 */
@Serializable
enum class DockMode {
    BAR,
    FLOATING,
    HIDDEN,
}

/** Stable ids; renaming one is a settings migration, so keep these strings stable. */
@Serializable
enum class QuickActionId(val key: String) {
    TOGGLE_LEARNING("toggle_learning"),
    TOGGLE_TRANSLATION("toggle_translation"),
    REPEAT_BLOCK("repeat_block"),
    STOP_AT_BLOCK_END("stop_at_block_end"),
    LAYOUT_MODE("layout_mode"),
    SUBTITLE_LIST("subtitle_list"),
    AI_EXPLAIN("ai_explain"),
    MY_WORDS("my_words"),
    PLAYLIST("playlist"),
    ASPECT_RATIO("aspect_ratio"),
    DECODER("decoder"),
    AUDIO_TRACK("audio_track"),
    SUBTITLE_TRACKS("subtitle_tracks"),
    SUBTITLE_TOOLS("subtitle_tools"),
    SPEED("speed"),
    PICTURE_IN_PICTURE("pip"),
    SHARE("share"),
    SETTINGS("settings"),
    ;

    companion object {
        fun fromKey(key: String): QuickActionId? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One quick-action button's own look and place (SUB-1 "size, transparency and position configurable
 * per button"). Floating position is a fraction of the player box so it survives rotation.
 */
@Serializable
data class QuickActionSpec(
    val dock: DockMode = DockMode.BAR,
    val sizeDp: Float = 40f,
    val transparencyPercent: Int = 0,
    val order: Int = 0,
    val xFraction: Float = 0f,
    val yFraction: Float = 0f,
    val iconTintArgb: Long? = null,
    val showLabel: Boolean = false,
) {
    init {
        require(sizeDp >= 24f && sizeDp <= 96f) { "quick action size must be between 24dp and 96dp" }
        require(transparencyPercent in 0..100) { "transparency is a percentage" }
    }

    companion object {
        /** Default layout: the two subtitle toggles and the learning tools in the top-left column (PLY-1). */
        val defaults: Map<String, QuickActionSpec> = mapOf(
            QuickActionId.TOGGLE_LEARNING, QuickActionId.TOGGLE_TRANSLATION, QuickActionId.LAYOUT_MODE,
            QuickActionId.REPEAT_BLOCK, QuickActionId.STOP_AT_BLOCK_END, QuickActionId.SUBTITLE_LIST,
            QuickActionId.AI_EXPLAIN, QuickActionId.MY_WORDS,
        ).mapIndexed { index, id ->
            id.key to QuickActionSpec(dock = DockMode.BAR, order = index)
        } + mapOf(
            QuickActionId.PLAYLIST to QuickActionSpec(dock = DockMode.BAR, order = 100),
            QuickActionId.ASPECT_RATIO to QuickActionSpec(dock = DockMode.BAR, order = 101),
            QuickActionId.DECODER to QuickActionSpec(dock = DockMode.BAR, order = 102),
            QuickActionId.AUDIO_TRACK to QuickActionSpec(dock = DockMode.BAR, order = 103),
            QuickActionId.SUBTITLE_TRACKS to QuickActionSpec(dock = DockMode.BAR, order = 104),
            QuickActionId.SUBTITLE_TOOLS to QuickActionSpec(dock = DockMode.BAR, order = 105),
            QuickActionId.SPEED to QuickActionSpec(dock = DockMode.BAR, order = 106),
            QuickActionId.PICTURE_IN_PICTURE to QuickActionSpec(dock = DockMode.BAR, order = 107),
            QuickActionId.SHARE to QuickActionSpec(dock = DockMode.BAR, order = 108),
            QuickActionId.SETTINGS to QuickActionSpec(dock = DockMode.BAR, order = 109),
        )
    }
}

/** The whole quick-action dock: the Quick Actions column under the top bar plus floating buttons. */
@Serializable
data class QuickActionSettings(
    val specs: Map<String, QuickActionSpec> = QuickActionSpec.defaults,
    val column: QuickActionColumn = QuickActionColumn.TOP_LEFT,
    val barIconsPerRow: Int = 8,
    val autoHideWithControls: Boolean = true,
) {
    fun spec(id: QuickActionId): QuickActionSpec = specs[id.key] ?: QuickActionSpec()

    fun updated(id: QuickActionId, value: QuickActionSpec): QuickActionSettings = copy(specs = specs + (id.key to value))

    fun visibleIn(mode: DockMode): List<Pair<QuickActionId, QuickActionSpec>> =
        specs.mapNotNull { (key, spec) -> QuickActionId.fromKey(key)?.let { it to spec } }
            .filter { it.second.dock == mode }
            .sortedWith(compareBy({ it.second.order }, { it.first.name }))
}

@Serializable
enum class QuickActionColumn {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}
