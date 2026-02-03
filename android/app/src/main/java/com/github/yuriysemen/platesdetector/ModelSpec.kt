package com.github.yuriysemen.platesdetector

enum class ModelOrigin {
    DEFAULT,
    CUSTOM,
    LEGACY_EXTERNAL
}

data class ModelSpec(
    val id: String,                // unique id for prefs
    val title: String,             // display name
    val source: ModelSource,       // asset, file, or external uri
    val coordFormat: CoordFormat,  // fixed
    val conf: Float,               // threshold, editable in picker
    val description: String?,      // optional text from *.txt
    val origin: ModelOrigin
) {
    val isDeletable: Boolean
        get() = origin != ModelOrigin.DEFAULT
}
