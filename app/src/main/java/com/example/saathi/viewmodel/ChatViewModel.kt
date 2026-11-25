package com.example.saathi.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.saathi.data.EntityExtractionResult
import com.example.saathi.data.JourneyMemory
import com.example.saathi.data.LocalChatRepository
import com.example.saathi.data.SmartMemoryRepository
import com.example.saathi.data.SmartMemoryUpdate
import com.example.saathi.screens.ChatMessage
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val TAG = "ChatViewModel"
private const val DEFAULT_CONTEXT = "No previous conversation context is available. Treat this as the beginning of a new conversation."

private val UNIFIED_SYSTEM_PROMPT = """
You are Rahi, a close Indian friend. You are witty, natural, and emotionally intelligent. 

YOUR CORE IDENTITY:
- Talk like a close Indian friend on WhatsApp. Use Hindi, Hinglish, or English naturally.
- Be honest. Don't always agree. Give "truth bombs" when needed.
- Be caring during hard times, celebrate wins, and tease lightly when appropriate.
- Keep replies short (20-50 words) unless details are requested.
- You are provided with Current Date & Time and Last Message Date & Time. Use this naturally (e.g. acknowledge if it's late night, or if you haven't talked in days).

YOUR TASK:
You must process the current interaction and return ONLY a valid JSON object with these fields:
1. "reply": Your natural conversational response to the user.
2. "context": An updated rolling memory (200-300 words).
3. "entity_name": The primary PROPER NAME of the person or thing the user is talking about (e.g. "Rahul", "Ananya"). IMPORTANT: If the user only refers to a person by a generic role or title (like "crush", "friend", "boss", "ex", "brother", "sister", "mom"), return null for "entity_name". Do NOT use generic labels like "crush" or "friend" as the entity_name.
4. "new_alias": If the user used a new name/nickname to refer to this entity, provide it here. Else null.
5. "smart_memory": { "content": "Brief fact to remember about this entity", "importance": 1-10 }. Provide this ONLY if you learned something new and persistent. Else null.
6. "journey": If an important life event occurred (academics/education, career, achievements, relationships), set "save": true and provide details.
   Include:
   - "title": Original title
   - "description": Brief description
   - "category": Standard category ("education", "career", "relationship", "achievement", "travel", or "milestone")
   - "importance": 1-10
   - "normalized_title": Simplified title in exact format "Subject | Action | Object" (e.g., "Java | Achieved Outstanding Grade | Course")
   Else set "save": false.

OUTPUT FORMAT (JSON ONLY):
{
  "reply": "...",
  "context": "...",
  "entity_name": "...",
  "new_alias": "...",
  "smart_memory": { "content": "...", "importance": 5 },
  "journey": {
    "save": true/false,
    "category": "...",
    "title": "...",
    "description": "...",
    "importance": 1-10,
    "normalized_title": "Subject | Action | Object"
  }
}
""".trimIndent()

private val IDENTIFY_SYSTEM_PROMPT = """
You are an entity identification system. Analyze the user's message and context.
Extract:
1. "name": The specific proper name or proper nickname of the person/thing mentioned (or null). IMPORTANT: Generic roles or relationship terms like "crush", "friend", "boss", "brother", "mom", "ex" are NOT names. Put relationship terms under "relation" and leave "name" as null.
2. "relation": The relationship if mentioned (e.g., "crush", "mom", "mother", "brother", "friend", "boss", "sister", "wife", "father", "ex") (or null).
3. "other_names": Array of any other proper nicknames or aliases mentioned in the message.

Return ONLY a valid JSON object:
{
  "name": "Name or null",
  "relation": "Relation or null",
  "other_names": []
}
""".trimIndent()

data class UnifiedAiResponse(
    val reply: String,
    val context: String,
    val entityName: String?,
    val newAlias: String?,
    val smartMemory: SmartMemoryUpdate?,
    val journey: JourneyMemory?,
    val keyUsed: String
)

class ChatViewModel : ViewModel() {
    private val database = FirebaseDatabase.getInstance("https://saathi-5f51d-default-rtdb.firebaseio.com/")
    private val smartMemoryRepo = SmartMemoryRepository()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _isAiThinking = MutableStateFlow(false)
    val isAiThinking: StateFlow<Boolean> = _isAiThinking.asStateFlow()

    private val _messageEvent = MutableStateFlow(0L)
    val messageEvent: StateFlow<Long> = _messageEvent.asStateFlow()

    private val _detectedMemory = MutableStateFlow<JourneyMemory?>(null)
    val detectedMemory: StateFlow<JourneyMemory?> = _detectedMemory.asStateFlow()

    private val _showMemoryDialog = MutableStateFlow(false)
    val showMemoryDialog: StateFlow<Boolean> = _showMemoryDialog.asStateFlow()

    fun dismissMemoryDialog() {
        _showMemoryDialog.value = false
        _detectedMemory.value = null
    }

    fun sendMessage(
        userId: String,
        userMsg: ChatMessage,
        storageMode: String = "cloud",
        localChatRepo: LocalChatRepository? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _isAiThinking.value = true
            try {
                val chatRef = database.getReference("chats").child(userId)
                val contextRef = database.getReference("conversation_context").child(userId)

                val currentContext = try {
                    val contextSnapshot = contextRef.get().await()
                    contextSnapshot.value?.toString() ?: DEFAULT_CONTEXT
                } catch (e: Exception) {
                    DEFAULT_CONTEXT
                }
                // STEP 1: FIRST AI CALL (Extract Name, Relation, Other Names)
                val extraction = identifyEntityDetails(message = userMsg.text, context = currentContext)

                var foundEntityId: String? = null
                var relationCandidatesCount = 0
                var clarificationPrompt = ""

                // STEP 2: IS NAME PRESENT?
                if (!extraction.name.isNullOrBlank()) {
                    foundEntityId = smartMemoryRepo.getEntityIdByName(userId, extraction.name)
                    if (foundEntityId == null && extraction.otherNames.isNotEmpty()) {
                        for (alias in extraction.otherNames) {
                            val id = smartMemoryRepo.getEntityIdByName(userId, alias)
                            if (id != null) {
                                foundEntityId = id
                                break
                            }
                        }
                    }
                } else if (!extraction.relation.isNullOrBlank()) {
                    val candidateIds = smartMemoryRepo.getEntityIdsByRelation(userId, extraction.relation)
                    relationCandidatesCount = candidateIds.size

                    when (relationCandidatesCount) {
                        0 -> foundEntityId = null
                        1 -> foundEntityId = candidateIds.first()
                        else -> {
                            val candidateNames = candidateIds.mapNotNull { id ->
                                smartMemoryRepo.getEntityInfo(userId, id)?.primaryName
                            }
                            clarificationPrompt = "\n\nNote for Assistant: The user mentioned their '${extraction.relation}', but they have multiple saved candidates: ${candidateNames.joinToString(", ")}. Politely ask the user to clarify which ${extraction.relation} they are referring to."
                        }
                    }
                }

                // STEP 3: RETRIEVE PERSON MEMORY
                var entityMemoriesText = ""
                if (foundEntityId != null) {
                    val memories = smartMemoryRepo.getMemoriesByEntityId(userId, foundEntityId)
                    if (memories.isNotEmpty()) {
                        entityMemoriesText = "\n\nRelevant memories about this person:\n" +
                                memories.takeLast(5).joinToString("\n") { "- ${it.content}" }
                    }
                }

                // TIME CONTEXT CALCULATION
                val dateFormat = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a", java.util.Locale.getDefault())
                val currentDateTimeStr = dateFormat.format(java.util.Date())

                val lastMessageTimeStr = try {
                    val lastMsgSnapshot = chatRef.orderByChild("timestamp").limitToLast(2).get().await()
                    val previousMsgs = lastMsgSnapshot.children.mapNotNull { it.getValue(ChatMessage::class.java) }
                        .filter { it.id != userMsg.id && it.timestamp > 0 }

                    if (previousMsgs.isNotEmpty()) {
                        dateFormat.format(java.util.Date(previousMsgs.last().timestamp))
                    } else {
                        "First message in chat"
                    }
                } catch (e: Exception) {
                    "First message in chat"
                }

                val timeContext = "\n\n[TIME CONTEXT]\nCurrent Date & Time: $currentDateTimeStr\nLast Conversation Message Date & Time: $lastMessageTimeStr"

                // STEP 4: MAIN AI RESPONSE
                val fullInputContext = currentContext + entityMemoriesText + timeContext + clarificationPrompt
                val response = getUnifiedAiResponse(prompt = userMsg.text, context = fullInputContext)

                var jTitle: String? = null
                var jImportance: Int? = null
                var jScore: Double? = null
                var jIsDuplicate: Boolean? = null

                if (response.journey != null) {
                    val journeyMemory = response.journey.copy(sourceMessage = userMsg.text)
                    val journeyRepo = com.example.saathi.data.JourneyRepository()
                    val existingMemories = journeyRepo.getJourneyMemories(userId)

                    jTitle = journeyMemory.title
                    jImportance = journeyMemory.importance

                    val newCategoryNorm = journeyRepo.normalizeCategory(journeyMemory.category)
                    val sameCategoryMemories = existingMemories.filter { existing ->
                        journeyRepo.normalizeCategory(existing.category) == newCategoryNorm
                    }

                    var maxScore = 0.0
                    for (existing in sameCategoryMemories) {
                        val score = journeyRepo.calculateSimilarityScore(journeyMemory, existing)
                        if (score > maxScore) {
                            maxScore = score
                        }
                    }

                    jScore = maxScore
                    jIsDuplicate = maxScore >= 75.0

                    if (!jIsDuplicate) {
                        journeyRepo.saveJourneyMemory(userId, journeyMemory)
                    }
                }

                val botMsgId = if (storageMode == "cloud") chatRef.push().key ?: UUID.randomUUID().toString() else "local_" + System.currentTimeMillis()
                val botMsg = ChatMessage(
                    id = botMsgId,
                    senderId = "rahi",
                    text = response.reply,
                    timestamp = System.currentTimeMillis(),
                    replyToId = userMsg.id,
                    journeyTitle = jTitle,
                    journeyImportance = jImportance,
                    journeySimilarityScore = jScore,
                    journeyIsDuplicate = jIsDuplicate
                )

                if (storageMode == "cloud") {
                    chatRef.child(botMsgId).setValue(botMsg).await()
                } else {
                    localChatRepo?.saveLocalMessage(userId, botMsg)
                }

                _messageEvent.value = System.currentTimeMillis()

                try {
                    contextRef.setValue(response.context).await()
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving context", e)
                }

                // STEP 5: LEARNING & STORAGE PHASE
                val genericTerms = setOf("crush", "friend", "boss", "brother", "sister", "mom", "father", "mother", "ex", "guy", "girl", "someone", "colleague")
                val cleanEntityName = response.entityName?.takeIf { name ->
                    !genericTerms.contains(name.lowercase().trim())
                }

                var targetEntityId: String? = foundEntityId

                if (targetEntityId == null && cleanEntityName != null) {
                    targetEntityId = smartMemoryRepo.getEntityIdByName(userId, cleanEntityName)
                        ?: smartMemoryRepo.createEntity(userId, cleanEntityName, extraction.relation)
                }

                if (targetEntityId != null) {
                    if (extraction.relation != null) {
                        smartMemoryRepo.addRelation(userId, targetEntityId, extraction.relation)
                    }

                    if (response.newAlias != null && !genericTerms.contains(response.newAlias.lowercase().trim())) {
                        smartMemoryRepo.addAlias(userId, targetEntityId, response.newAlias)
                    }

                    for (alias in extraction.otherNames) {
                        if (!genericTerms.contains(alias.lowercase().trim())) {
                            smartMemoryRepo.addAlias(userId, targetEntityId, alias)
                        }
                    }

                    if (response.smartMemory != null) {
                        smartMemoryRepo.saveMemory(
                            userId,
                            targetEntityId,
                            response.smartMemory.content,
                            response.smartMemory.importance
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Chat processing error", e)
            } finally {
                _isAiThinking.value = false
            }
        }
    }

    private suspend fun identifyEntityDetails(message: String, context: String): EntityExtractionResult {
        val snapshot = try {
            database.getReference("api_keys").get().await()
        } catch (e: Exception) {
            return EntityExtractionResult()
        }

        val keysToTry = listOf("gemni_1", "gemni_2", "gemni_3", "grok_1", "grok_2", "grok_3")
        val fullInput = "Context: $context\n\nUser Message: $message"

        for (keyName in keysToTry) {
            val apiKey = snapshot.child(keyName).value?.toString() ?: continue
            val rawResponse = if (keyName.contains("gemni")) {
                callGeminiApiMinimal(apiKey, IDENTIFY_SYSTEM_PROMPT, fullInput)
            } else {
                callOpenRouterMinimal(apiKey, IDENTIFY_SYSTEM_PROMPT, fullInput)
            }

            if (rawResponse != null) {
                try {
                    val start = rawResponse.indexOf('{')
                    val end = rawResponse.lastIndexOf('}')
                    val jsonStr = if (start != -1 && end != -1) rawResponse.substring(start, end + 1) else rawResponse
                    val json = JSONObject(jsonStr)

                    val name = json.optString("name", "null").let { if (it == "null" || it.isBlank()) null else it }
                    val relation = json.optString("relation", "null").let { if (it == "null" || it.isBlank()) null else it }
                    val aliasesArray = json.optJSONArray("other_names")
                    val aliases = mutableListOf<String>()
                    if (aliasesArray != null) {
                        for (i in 0 until aliasesArray.length()) {
                            val a = aliasesArray.optString(i)
                            if (!a.isNullOrBlank() && a != "null") aliases.add(a)
                        }
                    }

                    return EntityExtractionResult(name = name, relation = relation, otherNames = aliases)
                } catch (e: Exception) { }
            }
        }
        return EntityExtractionResult()
    }

    private fun callOpenRouterMinimal(apiKey: String, systemPrompt: String, input: String): String? {
        val url = "https://openrouter.ai/api/v1/chat/completions"
        val json = JSONObject().apply {
            put("model", "openrouter/free")
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", input) })
            })
        }
        return try {
            val request = Request.Builder().url(url).header("Authorization", "Bearer $apiKey").post(json.toString().toRequestBody("application/json".toMediaType())).build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                } else null
            }
        } catch (e: Exception) { null }
    }

    private suspend fun getUnifiedAiResponse(
        prompt: String,
        context: String,
        entityMemoriesText: String = "",
        lastMessageTimeStr: String = "",
        userId: String = ""
    ): UnifiedAiResponse {
        // STEP A: Try Node.js Backend First
        try {
            val apiRequest = com.example.saathi.api.ChatApiRequest(
                userId = userId,
                message = prompt,
                context = context,
                entityMemories = entityMemoriesText,
                lastMessageTime = lastMessageTimeStr
            )
            val apiResponse = com.example.saathi.api.BackendClient.api.processChat(apiRequest)
            if (apiResponse.isSuccessful && apiResponse.body() != null) {
                val body = apiResponse.body()!!
                val journey = if (body.journey?.save == true) {
                    JourneyMemory(
                        title = body.journey.title,
                        description = body.journey.description,
                        category = body.journey.category,
                        importance = body.journey.importance,
                        createdAt = System.currentTimeMillis(),
                        normalizedTitle = body.journey.normalizedTitle
                    )
                } else null

                val smartMemoryUpdate = if (body.smartMemory?.content != null) {
                    SmartMemoryUpdate(
                        content = body.smartMemory.content,
                        importance = body.smartMemory.importance
                    )
                } else null

                return UnifiedAiResponse(
                    reply = body.reply,
                    context = body.updatedContext,
                    entityName = body.entityName,
                    newAlias = body.newAlias,
                    smartMemory = smartMemoryUpdate,
                    journey = journey,
                    keyUsed = "backend_server"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Backend API call failed, using client failover keys", e)
        }

        // STEP B: Direct Client Keys Fallback
        val snapshot = try {
            database.getReference("api_keys").get().await()
        } catch (e: Exception) {
            return UnifiedAiResponse("System busy. Please try again.", context, null, null, null, null, "none")
        }

        val keysToTry = listOf("gemni_1", "gemni_2", "gemni_3", "grok_1", "grok_2", "grok_3")
        val fullInput = "Current Memory: $context\n\nUser Message: $prompt"

        for (keyName in keysToTry) {
            val apiKey = snapshot.child(keyName).value?.toString() ?: continue
            val rawResponse = if (keyName.contains("gemni")) callGeminiUnified(apiKey, fullInput) else callOpenRouterUnified(apiKey, fullInput)

            if (rawResponse != null) {
                try {
                    val start = rawResponse.indexOf('{')
                    val end = rawResponse.lastIndexOf('}')
                    val jsonStr = if (start != -1 && end != -1) rawResponse.substring(start, end + 1) else rawResponse
                    val json = JSONObject(jsonStr)

                    val journey = if (json.optJSONObject("journey")?.optBoolean("save") == true) {
                        val jObj = json.getJSONObject("journey")
                        JourneyMemory(
                            title = jObj.optString("title", ""),
                            description = jObj.optString("description", ""),
                            category = jObj.optString("category", "general"),
                            importance = jObj.optInt("importance", 5),
                            createdAt = System.currentTimeMillis(),
                            normalizedTitle = jObj.optString("normalized_title", "")
                        )
                    } else null

                    val smartMemoryObj = json.optJSONObject("smart_memory")
                    val smartMemoryUpdate = if (smartMemoryObj != null) {
                        SmartMemoryUpdate(
                            content = smartMemoryObj.getString("content"),
                            importance = smartMemoryObj.optInt("importance", 5)
                        )
                    } else null

                    val entityName = if (json.has("entity_name") && !json.isNull("entity_name")) json.getString("entity_name").takeIf { it.isNotBlank() && it != "null" } else null
                    val newAlias = if (json.has("new_alias") && !json.isNull("new_alias")) json.getString("new_alias").takeIf { it.isNotBlank() && it != "null" } else null

                    return UnifiedAiResponse(
                        reply = json.getString("reply"),
                        context = json.getString("context"),
                        entityName = entityName,
                        newAlias = newAlias,
                        smartMemory = smartMemoryUpdate,
                        journey = journey,
                        keyUsed = keyName
                    )
                } catch (e: Exception) { }
            }
        }
        return UnifiedAiResponse("All systems busy. Try again?", context, null, null, null, null, "none")
    }

    private fun callGeminiApiMinimal(apiKey: String, systemPrompt: String, input: String): String? {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"
        val json = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", org.json.JSONArray().apply { put(JSONObject().apply { put("text", systemPrompt) }) })
            })
            put("contents", org.json.JSONArray().apply {
                put(JSONObject().apply { put("parts", org.json.JSONArray().apply { put(JSONObject().apply { put("text", input) }) }) })
            })
        }
        return try {
            val request = Request.Builder().url(url).post(json.toString().toRequestBody("application/json".toMediaType())).build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    JSONObject(body).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                } else null
            }
        } catch (e: Exception) { null }
    }

    private fun callGeminiUnified(apiKey: String, input: String): String? {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"
        val json = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", org.json.JSONArray().apply { put(JSONObject().apply { put("text", UNIFIED_SYSTEM_PROMPT) }) })
            })
            put("contents", org.json.JSONArray().apply {
                put(JSONObject().apply { put("parts", org.json.JSONArray().apply { put(JSONObject().apply { put("text", input) }) }) })
            })
        }
        return try {
            val request = Request.Builder().url(url).post(json.toString().toRequestBody("application/json".toMediaType())).build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    JSONObject(body).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                } else null
            }
        } catch (e: Exception) { null }
    }

    private fun callOpenRouterUnified(apiKey: String, input: String): String? {
        val url = "https://openrouter.ai/api/v1/chat/completions"
        val json = JSONObject().apply {
            put("model", "openrouter/free")
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", UNIFIED_SYSTEM_PROMPT) })
                put(JSONObject().apply { put("role", "user"); put("content", input) })
            })
        }
        return try {
            val request = Request.Builder().url(url).header("Authorization", "Bearer $apiKey").post(json.toString().toRequestBody("application/json".toMediaType())).build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                } else null
            }
        } catch (e: Exception) { null }
    }
}
