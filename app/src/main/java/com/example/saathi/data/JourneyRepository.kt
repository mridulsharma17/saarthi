package com.example.saathi.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import java.util.Locale

class JourneyRepository {
    private val TAG = "JourneyRepository"
    private val database = FirebaseDatabase.getInstance("https://saathi-5f51d-default-rtdb.firebaseio.com/")
    private val usersRef = database.getReference("users")

    private fun getUserKeys(userId: String): List<String> {
        val keys = mutableListOf<String>()
        if (userId.isNotBlank()) keys.add(userId)
        
        val currentUser = FirebaseAuth.getInstance().currentUser
        val email = currentUser?.email
        if (!email.isNullOrBlank()) {
            val sanitizedEmail = email.replace(".", ",")
            if (!keys.contains(sanitizedEmail)) {
                keys.add(sanitizedEmail)
            }
        }
        return keys
    }

    fun normalizeCategory(category: String): String {
        val trimmed = category.lowercase(Locale.getDefault()).trim()
        return when {
            trimmed.contains("education") || trimmed.contains("academic") || trimmed.contains("study") || trimmed.contains("exam") || trimmed.contains("college") || trimmed.contains("grade") || trimmed.contains("school") -> "education"
            trimmed.contains("career") || trimmed.contains("job") || trimmed.contains("work") || trimmed.contains("placement") || trimmed.contains("internship") || trimmed.contains("office") -> "career"
            trimmed.contains("relation") || trimmed.contains("family") || trimmed.contains("love") || trimmed.contains("friend") || trimmed.contains("marriage") -> "relationship"
            trimmed.contains("achieve") || trimmed.contains("win") || trimmed.contains("goal") || trimmed.contains("award") || trimmed.contains("prize") -> "achievement"
            trimmed.contains("travel") || trimmed.contains("trip") || trimmed.contains("vacation") || trimmed.contains("tour") -> "travel"
            trimmed.contains("milestone") || trimmed.contains("life") -> "milestone"
            else -> if (trimmed.isBlank()) "general" else trimmed
        }
    }

    /**
     * Calculates single-field similarity score (0.0 to 100.0) between two text strings.
     * Rule 1: Exact Match = 100
     * Rule 2: Substring Containment = 85
     * Rule 3: Word Overlap Percentage
     * Rule 4: No Match = 0
     */
    fun calculateFieldScore(text1: String, text2: String): Double {
        val s1 = text1.lowercase(Locale.getDefault()).trim()
        val s2 = text2.lowercase(Locale.getDefault()).trim()

        if (s1.isEmpty() && s2.isEmpty()) return 50.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        // Rule 1: Exact Match
        if (s1 == s2) return 100.0

        // Rule 2: Substring Containment
        if (s1.contains(s2) || s2.contains(s1)) return 85.0

        // Rule 3: Word Overlap Percentage
        val words1 = s1.split("\\s+".toRegex()).filter { it.length > 1 }
        val words2 = s2.split("\\s+".toRegex()).filter { it.length > 1 }

        if (words1.isEmpty() || words2.isEmpty()) return 0.0

        val matchingWords = words1.count { words2.contains(it) }
        val totalMaxWords = maxOf(words1.size, words2.size)

        val overlapRatio = (matchingWords.toDouble() / totalMaxWords) * 100.0
        return if (overlapRatio >= 30.0) overlapRatio else 0.0
    }

    /**
     * Calculates weighted similarity score out of 100 between two Journey memories.
     * Subject: 40% Weight
     * Action: 40% Weight
     * Object: 20% Weight
     */
    fun calculateSimilarityScore(m1: JourneyMemory, m2: JourneyMemory): Double {
        // Fallback: If original title or source message is an exact match
        if (m1.title.isNotBlank() && m1.title.equals(m2.title, ignoreCase = true)) return 100.0
        if (m1.sourceMessage.isNotBlank() && m1.sourceMessage.equals(m2.sourceMessage, ignoreCase = true)) return 100.0

        val subjectScore = calculateFieldScore(m1.subject, m2.subject)
        val actionScore = calculateFieldScore(m1.action, m2.action)
        val objectScore = calculateFieldScore(m1.objectTerm, m2.objectTerm)

        val totalScore = (subjectScore * 0.40) + (actionScore * 0.40) + (objectScore * 0.20)
        Log.d(TAG, "Similarity score between [${m1.normalizedTitle}] and [${m2.normalizedTitle}]: $totalScore (S:$subjectScore, A:$actionScore, O:$objectScore)")
        return totalScore
    }

    /**
     * Checks if a new Journey memory is a duplicate of an existing Journey memory in the SAME category.
     */
    fun isDuplicateJourney(newMemory: JourneyMemory, existingMemories: List<JourneyMemory>, threshold: Double = 75.0): Boolean {
        val newCategoryNorm = normalizeCategory(newMemory.category)

        // Filter existing memories to ONLY compare entries in the exact same category
        val sameCategoryMemories = existingMemories.filter { existing ->
            normalizeCategory(existing.category) == newCategoryNorm
        }

        return sameCategoryMemories.any { existing ->
            calculateSimilarityScore(newMemory, existing) >= threshold
        }
    }

    suspend fun saveJourneyMemory(userId: String, memory: JourneyMemory): Boolean {
        return try {
            val existingMemories = getJourneyMemories(userId)

            // Duplicate Guard: Run weighted 3-field comparison algorithm
            if (isDuplicateJourney(memory, existingMemories, threshold = 75.0)) {
                Log.d(TAG, "Duplicate Journey memory detected for title: '${memory.title}'. Skipping save to Firebase.")
                return true
            }

            val journeyType = normalizeCategory(memory.category)
            val userKeys = getUserKeys(userId)
            val primaryKey = userKeys.firstOrNull() ?: userId

            val typeRef = usersRef.child(primaryKey).child("journey").child(journeyType)
            val newMemoryRef = if (memory.id.isEmpty()) typeRef.push() else typeRef.child(memory.id)
            val finalMemory = memory.copy(
                id = newMemoryRef.key ?: "",
                category = journeyType
            )
            newMemoryRef.setValue(finalMemory).await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving Journey memory", e)
            false
        }
    }

    suspend fun getJourneyMemories(userId: String): List<JourneyMemory> {
        val userKeys = getUserKeys(userId)
        val allMemories = mutableListOf<JourneyMemory>()

        for (userKey in userKeys) {
            try {
                val snapshot = usersRef.child(userKey).child("journey").get().await()
                if (snapshot.exists()) {
                    snapshot.children.forEach { typeChild ->
                        if (typeChild.hasChildren()) {
                            typeChild.children.forEach { memoryChild ->
                                val memory = memoryChild.getValue(JourneyMemory::class.java)
                                if (memory != null && memory.title.isNotBlank()) {
                                    allMemories.add(memory)
                                }
                            }
                        } else {
                            val memory = typeChild.getValue(JourneyMemory::class.java)
                            if (memory != null && memory.title.isNotBlank()) {
                                allMemories.add(memory)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Continue checking next key
            }
        }

        return allMemories.distinctBy { if (it.id.isNotEmpty()) it.id else "${it.title}_${it.createdAt}" }
            .sortedWith(
                compareByDescending<JourneyMemory> { it.importance }
                    .thenByDescending { it.createdAt }
            )
    }

    suspend fun getJourneyMemoriesByType(userId: String, journeyType: String): List<JourneyMemory> {
        val allMemories = getJourneyMemories(userId)
        val normalizedType = normalizeCategory(journeyType)
        
        return allMemories.filter { 
            normalizeCategory(it.category) == normalizedType || normalizedType == "all"
        }
    }
}
