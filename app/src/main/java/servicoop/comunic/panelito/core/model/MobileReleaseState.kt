package servicoop.comunic.panelito.core.model

data class MobileReleaseState(
    val checking: Boolean = false,
    val updateRequired: Boolean = false,
    val updateMandatory: Boolean = false,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val apkUrl: String? = null,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    val maxOmissions: Int = 0,
    val omissionsUsed: Int = 0,
    val error: String? = null,
)
