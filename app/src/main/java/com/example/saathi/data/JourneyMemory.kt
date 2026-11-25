package com.example.saathi.data

data class JourneyMemory(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val category: String = "",
    val importance: Int = 0,
    val createdAt: Long = 0,
    val sourceMessage: String = "",
    val aiGenerated: Boolean = true,
    val normalizedTitle: String = ""
) {
    val subject: String
        get() {
            val parts = normalizedTitle.split("|")
            return if (parts.isNotEmpty()) parts[0].trim() else ""
        }

    val action: String
        get() {
            val parts = normalizedTitle.split("|")
            return if (parts.size > 1) parts[1].trim() else ""
        }

    val objectTerm: String
        get() {
            val parts = normalizedTitle.split("|")
            return if (parts.size > 2) parts[2].trim() else ""
        }
}
