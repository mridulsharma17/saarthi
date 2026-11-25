package com.example.saathi.api

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

// Request Model for POST /api/chat
data class ChatApiRequest(
    val userId: String,
    val message: String,
    val context: String,
    val entityMemories: String = "",
    val lastMessageTime: String
)

// Inner Models for POST /api/chat Response
data class EntityExtractionData(
    val name: String? = null,
    val relation: String? = null,
    val otherNames: List<String> = emptyList()
)

data class SmartMemoryData(
    val content: String? = null,
    val importance: Int = 1
)

data class JourneyApiData(
    val save: Boolean = false,
    val category: String = "",
    val title: String = "",
    val description: String = "",
    val importance: Int = 0,
    val normalizedTitle: String = ""
)

// Response Model for POST /api/chat
data class ChatApiResponse(
    val reply: String = "",
    val updatedContext: String = "",
    val entityExtraction: EntityExtractionData? = null,
    val entityName: String? = null,
    val newAlias: String? = null,
    val smartMemory: SmartMemoryData? = null,
    val journey: JourneyApiData? = null
)

// Request & Response Models for POST /api/inactivity-checkin
data class InactivityApiRequest(
    val userId: String,
    val context: String,
    val lastUserMessageDate: String,
    val currentDate: String = "",
    val timeDiffText: String = ""
)

data class InactivityApiResponse(
    val reply: String = "",
    val updatedContext: String = ""
)

// Retrofit Interface
interface SaathiApiService {
    @POST("api/chat")
    suspend fun processChat(@Body request: ChatApiRequest): Response<ChatApiResponse>

    @POST("api/inactivity-checkin")
    suspend fun generateInactivityCheckin(@Body request: InactivityApiRequest): Response<InactivityApiResponse>
}

// Retrofit Client
object BackendClient {
    private const val BASE_URL = "https://saathi-backend-3j4n.onrender.com/"

    private val okHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    val api: SaathiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SaathiApiService::class.java)
    }
}
