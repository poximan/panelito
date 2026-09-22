package servicoop.comunic.panelito.core.model

import java.io.Serializable

data class EmailEvent(
    val id: String,
    val type: String,
    val subject: String,
    val status: String,
    val timestamp: String,
    val detail: String = "",
) : Serializable
