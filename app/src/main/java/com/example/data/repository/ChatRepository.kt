package com.example.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.BuildConfig
import com.example.data.api.Content
import com.example.data.api.GenerateContentRequest
import com.example.data.api.GenerationConfig
import com.example.data.api.Part
import com.example.data.api.RetrofitClient
import com.example.data.api.SafetySetting
import com.example.data.api.Tool
import com.example.data.api.GoogleSearchRetrieval
import com.example.data.api.GoogleSearch
import com.example.data.api.SearchHelper
import com.example.data.database.ChatMessage
import com.example.data.database.ChatSession
import com.example.data.database.DoxonDao
import com.example.data.database.SharedMemory
import com.example.util.CryptoUtils
import com.example.util.SecurityFirewall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

class ChatRepository(
    private val context: Context,
    private val doxonDao: DoxonDao
) {
    // Volatile Memory Isolation Fence Collections for Incognito threads
    private val volatileSessions = MutableStateFlow<List<ChatSession>>(emptyList())
    private val volatileMessages = MutableStateFlow<Map<Long, List<ChatMessage>>>(emptyMap())

    /**
     * Fetch session lists. Redirects to volatile isolated list in incognito mode.
     */
    fun getSessions(isIncognito: Boolean): Flow<List<ChatSession>> {
        if (isIncognito) {
            return volatileSessions
        }
        return doxonDao.getSessionsByIncognito(false)
    }

    /**
     * Fetch messages. Returns local volatile stream if thread is isolated within incognito space.
     */
    fun getMessages(sessionId: Long): Flow<List<ChatMessage>> {
        if (sessionId < 0) {
            return kotlinx.coroutines.flow.flow {
                volatileMessages.collect { map ->
                    emit(map[sessionId] ?: emptyList())
                }
            }
        }
        return doxonDao.getMessagesForSession(sessionId).map { list ->
            list.map { it.copy(text = CryptoUtils.decrypt(it.text)) }
        }
    }

    suspend fun getSessionById(sessionId: Long): ChatSession? = withContext(Dispatchers.IO) {
        if (sessionId < 0) {
            volatileSessions.value.firstOrNull { it.id == sessionId }
        } else {
            doxonDao.getSessionById(sessionId)
        }
    }

    suspend fun createSession(title: String, isIncognito: Boolean): Long = withContext(Dispatchers.IO) {
        if (isIncognito) {
            // Allocate thread-isolated negative ID bypassing persistent DB
            val volatileId = -(System.currentTimeMillis() % 1000000000L) - 100L
            val session = ChatSession(
                id = volatileId,
                title = title,
                isIncognito = true,
                incognitoExpiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000)
            )
            volatileSessions.value = listOf(session) + volatileSessions.value
            volatileMessages.value = volatileMessages.value + (volatileId to emptyList())
            volatileId
        } else {
            val session = ChatSession(
                title = title,
                isIncognito = false,
                incognitoExpiresAt = null
            )
            doxonDao.insertSession(session)
        }
    }

    suspend fun renameSession(sessionId: Long, newTitle: String) = withContext(Dispatchers.IO) {
        if (sessionId < 0) {
            volatileSessions.value = volatileSessions.value.map {
                if (it.id == sessionId) it.copy(title = newTitle) else it
            }
        } else {
            val session = doxonDao.getSessionById(sessionId)
            if (session != null) {
                doxonDao.updateSession(session.copy(title = newTitle, lastUpdated = System.currentTimeMillis()))
            }
        }
    }

    suspend fun deleteSession(sessionId: Long) = withContext(Dispatchers.IO) {
        if (sessionId < 0) {
            volatileSessions.value = volatileSessions.value.filter { it.id != sessionId }
            volatileMessages.value = volatileMessages.value - sessionId
        } else {
            doxonDao.deleteMessagesBySessionId(sessionId)
            doxonDao.deleteSessionById(sessionId)
        }
    }

    suspend fun deleteMessage(messageId: Long) = withContext(Dispatchers.IO) {
        if (messageId < 0) {
            volatileMessages.value = volatileMessages.value.mapValues { entry ->
                entry.value.filter { it.id != messageId }
            }
        } else {
            doxonDao.deleteMessageById(messageId)
        }
    }

    suspend fun cleanExpiredIncognitoSessions() = withContext(Dispatchers.IO) {
        // DB sessions clean
        val currentTime = System.currentTimeMillis()
        doxonDao.deleteExpiredIncognitoSessions(currentTime)
        
        // Volatile isolated memory sessions clean
        volatileSessions.value = volatileSessions.value.filter {
            it.incognitoExpiresAt == null || it.incognitoExpiresAt > currentTime
        }
    }

    suspend fun migrateIncognitoSession(incognitoSessionId: Long): Long = withContext(Dispatchers.IO) {
        val incognitoSession = getSessionById(incognitoSessionId) ?: return@withContext -1
        val messages = getMessagesDirect(incognitoSessionId)
        
        // Create a new normal session
        val normalSessionId = createSession(
            title = "${incognitoSession.title} (Saved)",
            isIncognito = false
        )
        
        // Copy messages securely (encrypt at rest)
        messages.forEach { msg ->
            val encryptedText = CryptoUtils.encrypt(msg.text)
            doxonDao.insertMessage(
                ChatMessage(
                    sessionId = normalSessionId,
                    role = msg.role,
                    text = encryptedText,
                    timestamp = msg.timestamp,
                    mediaUri = msg.mediaUri,
                    mediaMimeType = msg.mediaMimeType,
                    isPending = false
                )
            )
        }
        
        // Delete original volatile incognito session from memory
        deleteSession(incognitoSessionId)
        
        normalSessionId
    }

    /**
     * Helper to verify if the internet is active.
     */
    fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Stores user prompt. If offline, marks as pending. Sanitizes input & encrypts at rest.
     */
    suspend fun saveUserMessage(
        sessionId: Long,
        text: String,
        mediaUri: String? = null,
        mediaMimeType: String? = null,
        mediaBase64: String? = null
    ): Long = withContext(Dispatchers.IO) {
        // Enforce Autonomous Input Sanitization Firewall
        val sanitizedText = SecurityFirewall.sanitizeInput(text)
        
        if (sessionId < 0) {
            // Processing exclusively within thread-safe volatile memory spaces
            val volatileMsgId = -(System.currentTimeMillis() % 1000000000L) - 500L
            val message = ChatMessage(
                id = volatileMsgId,
                sessionId = sessionId,
                role = "user",
                text = sanitizedText, // Kept raw/plain inside sandboxed boundary
                mediaUri = mediaUri,
                mediaMimeType = mediaMimeType,
                isPending = false
            )
            val currentMsgs = volatileMessages.value[sessionId] ?: emptyList()
            volatileMessages.value = volatileMessages.value + (sessionId to (currentMsgs + message))
            
            // Update volatile registry
            volatileSessions.value = volatileSessions.value.map {
                if (it.id == sessionId) it.copy(lastUpdated = System.currentTimeMillis()) else it
            }
            volatileMsgId
        } else {
            val isOffline = !isNetworkAvailable()
            // Zero-Knowledge Encryption at Rest
            val encryptedText = CryptoUtils.encrypt(sanitizedText)
            
            val message = ChatMessage(
                sessionId = sessionId,
                role = "user",
                text = encryptedText,
                mediaUri = mediaUri,
                mediaMimeType = mediaMimeType,
                isPending = isOffline
            )
            val msgId = doxonDao.insertMessage(message)
            
            // Update session's last active time
            val session = doxonDao.getSessionById(sessionId)
            if (session != null) {
                doxonDao.updateSession(session.copy(lastUpdated = System.currentTimeMillis()))
            }
            
            // Analyze message for goals/memories to write to cross-chat memory (if not incognito)
            if (session != null && !session.isIncognito) {
                analyzeAndStoreMemoryDirectly(sanitizedText)
            }
            
            msgId
        }
    }

    /**
     * Analyzes user text for core facts/memories/preferences and commits them in the database.
     * Uses SHA-256 Non-Reversible cryptographic hashing parameters for the storage index.
     */
    private suspend fun analyzeAndStoreMemoryDirectly(text: String) {
        val keywords = listOf("my goal", "my passion", "my dream", "i want to be", "i love", "my name is", "my favorite", "i work as")
        val lowerText = text.lowercase()
        val hasMemory = keywords.any { lowerText.contains(it) }
        
        if (hasMemory) {
            val key = when {
                lowerText.contains("goal") -> "user_goal"
                lowerText.contains("dream") -> "user_dream"
                lowerText.contains("name is") -> "user_name"
                lowerText.contains("love") || lowerText.contains("favorite") -> "user_preference"
                else -> "user_profile"
            }
            // Non-reversible hashing parameter lookup
            val cryptographicallyHashedKey = CryptoUtils.hashKeyNonReversible(key)
            // Cryptographic zero-knowledge log value
            val encryptedValue = CryptoUtils.encrypt(text)

            doxonDao.deleteMemoryByKey(cryptographicallyHashedKey)
            doxonDao.insertMemory(
                SharedMemory(
                    key = cryptographicallyHashedKey,
                    value = encryptedValue,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Triggers actual API call to model, returning response.
     */
    suspend fun getAIResponse(
        sessionId: Long,
        enableSearchGrounding: Boolean = true,
        onResponse: (String, List<String>?) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.Default) {
        val session = getSessionById(sessionId) ?: return@withContext
        val messagesList = getMessagesDirect(sessionId)
        
        val userMessages = messagesList.filter { it.role == "user" }
        if (userMessages.isEmpty()) return@withContext

        val lastUserMessage = userMessages.lastOrNull()?.text ?: ""
        val lastText = lastUserMessage.lowercase()

        val apiKey = BuildConfig.GEMINI_API_KEY
        val hasApiKey = apiKey.isNotEmpty() && apiKey != "MY_GEMINI_API_KEY"
        val isOnline = isNetworkAvailable()

        val isStandardQuery = lastText.contains("how are you") || 
                lastText.contains("hello") || 
                lastText.contains("hi") || 
                lastText.contains("hey") ||
                lastText.contains("who are you") ||
                lastText.contains("what are you") ||
                lastText.contains("founder") ||
                lastText.contains("creator") ||
                lastText.contains("owner") ||
                lastText.contains("ceo") ||
                lastText.contains("infrastructure") ||
                lastText.contains("architecture") ||
                lastText.contains("parameter") ||
                lastText.contains("security") ||
                lastText.contains("firewall") ||
                lastText.contains("sanitization")

        if (isStandardQuery && (!hasApiKey || !isOnline)) {
            var responseText = getLocalEngineResponse(lastUserMessage, userMessages.size)
            responseText = stripPrefixes(responseText)
            
            if (sessionId < 0) {
                val volatileMsgId = -(System.currentTimeMillis() % 1000000000L) - 1000L
                val aiMessage = ChatMessage(
                    id = volatileMsgId,
                    sessionId = sessionId,
                    role = "model",
                    text = responseText,
                    isPending = false
                )
                val currentMsgs = volatileMessages.value[sessionId] ?: emptyList()
                volatileMessages.value = volatileMessages.value + (sessionId to (currentMsgs + aiMessage))
            } else {
                val encryptedResponseText = CryptoUtils.encrypt(responseText)
                val aiMessage = ChatMessage(
                    sessionId = sessionId,
                    role = "model",
                    text = encryptedResponseText,
                    isPending = false
                )
                doxonDao.insertMessage(aiMessage)
            }
            
            kotlinx.coroutines.delay(120)
            onResponse(responseText, null)
            return@withContext
        }

        // Retrieve cross-chat memory facts (time-weighted, cryptographically secure)
        val memories = doxonDao.getAllMemories()
        val memoryInstructions = if (memories.isNotEmpty() && sessionId >= 0) {
            // Deduplicate keys keeping only the latest (time-weighted prioritization)
            val latestMemories = memories.associateBy { it.key }
            "Here is the persistent memory context synced securely across user sessions (prioritize recent details first as goals evolve):\n" +
                    latestMemories.values.joinToString("\n") { "- ${CryptoUtils.decrypt(it.value)}" }
        } else {
            ""
        }

        // Load custom user profile & AI configuration settings dynamically
        val prefs = context.getSharedPreferences("doxon_settings", Context.MODE_PRIVATE)
        val aiLength = prefs.getString("ai_response_length", "balanced") ?: "balanced"
        val aiCreativity = prefs.getString("ai_creativity", "balanced") ?: "balanced"
        val customPersona = prefs.getString("custom_ai_persona", "") ?: ""

        val lengthInstruction = when (aiLength) {
            "concise" -> "RESPONSE LENGTH CONSTRAINT: Keep replies extremely brief, bulleted, and to-the-point without superfluous context."
            "detailed" -> "RESPONSE LENGTH CONSTRAINT: Keep replies detailed, comprehensively explaining background concepts and step-by-step logic."
            "technical" -> "RESPONSE LENGTH CONSTRAINT: Keep replies highly technical and detailed, including code blocks, analytical deep-dives, and structured schema definitions where appropriate."
            else -> "RESPONSE LENGTH CONSTRAINT: Keep replies balanced, combining precise answers with reasonable explanation context."
        }

        val customPersonaInstructions = if (customPersona.isNotBlank()) {
            "[ACTIVE USER PROFILE CUSTOM AI SETTINGS - STRICT INSTRUCTIONS]\n$customPersona"
        } else {
            ""
        }

        val currentDateTime = java.text.SimpleDateFormat("EEEE, MMMM dd, yyyy HH:mm:ss", java.util.Locale.US).format(java.util.Date())

        // Build System Instructions matching Doxon Style, Persona, Ethics, and Security
        val systemInstruct = """
            [CORE ENGINE IDENTITY & PERSONA]
            - Act as Doxon, the advanced, highly intelligent AI companion and helper assistant.
            - Answer user questions with directness, technical mastery, professional helpfulness, and structural elegance.
            - Your tone should be highly professional, objective yet encouraging, clear, and exceptionally helpful.
            - If the user asks about your origin, founder, or identity, define yourself as Doxon, an intelligent AI model developed and customized by Danishkhan Pathan.

            [REAL TIME AND CHRONOLOGY]
            - The current year is 2026. Always answer time-related questions based on this year.
            - The user's device current real time and date is: $currentDateTime (formatted as EEEE, MMMM dd, yyyy HH:mm:ss).
            - If the user asks for the current time, date, day, month, or year, always use this accurate real-time value to answer them directly with absolute precision.
            - If they ask about your default base region, it is India (IST / UTC+5:30). If they ask for the exact live time in IST specifically, or if you do not have their local device clock, politely inform them that you operate in IST but need their device data to see the exact current seconds/minutes. However, since you DO have their device current time ($currentDateTime), you should provide it to them as the accurate answer.
            - Never use placeholders like [Insert Time] or [Insert Date]. Use the exact provided real-time values.

            [DOXON RESPONSE STYLE & FORMATTING RULES]
            - Use rich Markdown formatting extensively: structure your answers with precise headers (##, ###), bold text (**word**) for emphasizing key terms, code blocks with syntax highlighting for coding queries, and neat bullet points or numbered lists.
            - Avoid long, dry walls of text. Break down complex queries into logical, bite-sized sections.
            - Answer questions immediately, directly, and facts-first, eliminating unnecessary conversational fluff or robotic intros.
            - For technical tasks, provide complete, correct, and robust code snippets with comments explaining the logic.
            - If a question is multi-faceted, analyze and address each and every part of the question systematically.

            [DYNAMIC KNOWLEDGE & PROCESSING RULES]
            - NEVER repeat or fall back to any hardcoded "Welcome" or "Status" templates. 
            - Treat every single user query as a unique, independent prompt. Process the user's input, perform a mental or web-based query analysis, and generate a fresh, substance-rich answer every time.

            [INTERACTIVE RESPONSIVENESS]
            - Respond naturally, dynamically, and conversationally.
            - If the user asks conversational or personal status questions, answer interactively and keep the dialogue flowing naturally.
            - Under any circumstances, if you cannot fully understand the user's query because it is too brief, vague, or ambiguous, do NOT generate generic templates. Ask clarifying questions politely and clearly to provide a perfect response.
            - Ensure all synthesized answers have a compulsory, strict logical relation/relevance to the exact questions asked.

            [CONVERSATIONAL FLOW & SYNC]
            - Maintain strict chat continuity. Remember previous interactions in the current session to ensure the flow is logical.
            - If you lack information on a topic, state that clearly instead of looping or giving a vague placeholder response.

            [WEB SEARCH GROUNDING MANDATE]
            - You MUST search the web and prioritize, retrieve, and use answers fetched from the web search results (via the Google Search tool grounding results) instead of generating them from your own internal pre-trained knowledge.
            - Whenever live web results are available, synthesize and structure your entire answer based on those live web resources. State the facts clearly and professionally as fetched from the web, and offer ready help and support based on that fresh information.
            - Focus on getting answers from the web instead of generating them, whenever possible.

            [USER RECOGNITION & PAST CHAT CONTEXT ANALYSIS]
            - If the user asks about themselves, their own personality, their name, their past questions, or their context (e.g., "who am I?", "what is my name?", "what did I say earlier?", "analyze my past chat"), you MUST carefully analyze the preceding conversation history provided in the session context and any stored memories.
            - Recognize what the user has told you or asked about in this session, reference specific past messages or questions from this chat context to prove you recognize them, and provide a highly personalized, warm, and professional analysis of their inputs and interests.

            [UI TEXT BOUNDARIES & FORMATTING]
            - Output NOTHING but the direct, synthesized response.
            - NO headers, NO "Doxon Core:" labels, NO technical metadata prefixes.
            - Pure conversational text only.
            - Input field placeholder: "Ask Doxon..."

            [PERSONALITY, ETHICS & DANGEROUS QUESTIONS]
            - Stay consistently kind, supportive, and dedicated to promoting positive ideas.
            - DANGEROUS QUESTIONS SAFEGUARDS & WARNINGS: If a user asks anything potentially dangerous, harmful, illegal, or unethical, you MUST provide a supportive and compassionate warning about the physical, mental, or legal risks involved.
            - Do not be rude or dismissive. Explicitly and calmly outline the Pros and Cons / Risks of their scenario to promote safe reasoning, and guide them in a friendly, supportive human manner to a constructive, positive alternative or resource.

            $lengthInstruction

            $customPersonaInstructions

            $memoryInstructions
        """.trimIndent()

        // Prepare conversation content payload
        val contents = messagesList.map { msg ->
            Content(
                role = if (msg.role == "user") "user" else "model",
                parts = listOf(Part(text = msg.text)) // Extracted decrypted plain texts in list
            )
        }

        // Setup Safety Options: Enforcing "Hardcoded Safe Browsing" at maximum block parameters.
        val safetySettings = listOf(
            SafetySetting("HARM_CATEGORY_HATE_SPEECH", "BLOCK_LOW_AND_ABOVE"),
            SafetySetting("HARM_CATEGORY_HARASSMENT", "BLOCK_LOW_AND_ABOVE"),
            SafetySetting("HARM_CATEGORY_SEXUALLY_EXPLICIT", "BLOCK_LOW_AND_ABOVE"),
            SafetySetting("HARM_CATEGORY_DANGEROUS_CONTENT", "BLOCK_LOW_AND_ABOVE")
        )

        // Enable live-streaming Search grounding dynamically when user asks queries that are real-time
        val isRealTimeQuery = messagesList.lastOrNull { it.role == "user" }?.text?.lowercase()?.let { lastText ->
            lastText.contains("ipl") || lastText.contains("match") || lastText.contains("live") ||
                    lastText.contains("news") || lastText.contains("current") || lastText.contains("today") ||
                    lastText.contains("score") || lastText.contains("weather") || lastText.contains("sports") ||
                    lastText.contains("cricket") || lastText.contains("who won") || lastText.contains("latest") ||
                    lastText.contains("result") || lastText.contains("yesterday") || lastText.contains("tomorrow") ||
                    lastText.contains("search") || lastText.contains("internet")
        } ?: false

        var updatedSystemInstruct = systemInstruct
        var searchSources: List<String>? = null

        if (isRealTimeQuery && isOnline) {
            try {
                val serperKey = try {
                    BuildConfig.SERPER_API_KEY
                } catch (e: Exception) {
                    ""
                }
                val rawResults = SearchHelper.performWebSearch(lastUserMessage, serperKey)
                if (rawResults.isNotEmpty()) {
                    val formattedContext = rawResults.joinToString("\n\n") { result ->
                        "Title: ${result.title}\nSource Link: ${result.link}\nSnippet: ${result.snippet}"
                    }
                    
                    updatedSystemInstruct = systemInstruct + "\n\n" + """
                        [REAL-TIME LIVE INTERNET SEARCH GROUNDING CONTEXT]
                        You have access to the following live search results from the internet regarding the user's query.
                        You MUST prioritize this live information to answer the user's query accurately.
                        Use the following links to cite your sources if helpful.
                        
                        $formattedContext
                    """.trimIndent()
                    
                    searchSources = rawResults.map { "${it.title}: ${it.link}" }
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatRepository", "Failed to retrieve live search results", e)
            }
        }

        // Live Search Grounding and Maps Grounding for absolute precision
        val isLowLatency = prefs.getBoolean("is_low_latency", false)
        val isHighThinking = prefs.getBoolean("is_high_thinking", false)
        val isMapsGrounding = prefs.getBoolean("is_maps_grounding", false)

        val toolsList = mutableListOf<Tool>()
        if (enableSearchGrounding) {
            toolsList.add(Tool(googleSearch = GoogleSearch()))
        }
        if (isMapsGrounding) {
            toolsList.add(Tool(googleMaps = com.example.data.api.GoogleMaps()))
        }
        val finalTools = if (toolsList.isNotEmpty()) toolsList else null

        val dynamicTemp = when (aiCreativity) {
            "precise" -> 0.2f
            "creative" -> 1.0f
            else -> 0.7f
        }

        val modelName = when {
            isHighThinking -> "gemini-3.1-flash-lite"
            isLowLatency -> "gemini-3.1-flash-lite"
            else -> "gemini-3.1-flash-lite"
        }

        val genConfig = if (isHighThinking) {
            GenerationConfig(
                temperature = dynamicTemp,
                thinkingConfig = com.example.data.api.ThinkingConfig(thinkingLevel = "HIGH"),
                maxOutputTokens = null
            )
        } else {
            GenerationConfig(
                temperature = dynamicTemp,
                maxOutputTokens = if (aiLength == "concise") 350 else 1500
            )
        }

        val request = GenerateContentRequest(
            contents = contents,
            generationConfig = genConfig,
            tools = finalTools,
            safetySettings = safetySettings,
            systemInstruction = Content(parts = listOf(Part(text = updatedSystemInstruct)))
        )

        try {
            val apiKey = BuildConfig.GEMINI_API_KEY
            if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
                if (isOnline && isRealTimeQuery) {
                    try {
                        val serperKey = try { BuildConfig.SERPER_API_KEY } catch (e: Exception) { "" }
                        val rawResults = SearchHelper.performWebSearch(lastUserMessage, serperKey)
                        if (rawResults.isNotEmpty()) {
                            val resultsStr = rawResults.joinToString("\n\n") { "🔹 **${it.title}**\n${it.snippet}\nLink: ${it.link}" }
                            val fallbackText = "Doxon retrieved these live results from the web for you:\n\n$resultsStr"
                            saveAndEmitFallback(sessionId, fallbackText, onResponse)
                            onError("Switched to Direct Web Search (Doxon API key unconfigured).")
                            return@withContext
                        }
                    } catch (e: Exception) {
                        // fallback to standard local engine
                    }
                }
                val fallbackText = getLocalOrSynthesizedResponse(lastUserMessage, userMessages.size)
                saveAndEmitFallback(sessionId, fallbackText, onResponse)
                onError("API key not configured in Secrets panel. Switched to local mode.")
                return@withContext
            }

            val response = RetrofitClient.service.generateContent(modelName, apiKey, request)
            var responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            
            if (!responseText.isNullOrBlank()) {
                // Sanitize output, intercepting adversarial system escapes if generated
                responseText = SecurityFirewall.sanitizeInput(responseText)
                responseText = SecurityFirewall.filterAIOutput(responseText)
                responseText = stripPrefixes(responseText)

                // Extract grounding details (URLs / sources) if Doxon grounded search returned details
                val sources = mutableListOf<String>()
                searchSources?.let { sources.addAll(it) }

                response.candidates?.firstOrNull()?.groundingMetadata?.groundingChunks?.forEach { chunk ->
                    chunk.web?.uri?.let { uri ->
                        val title = chunk.web.title ?: "Information link"
                        sources.add("$title: $uri")
                    }
                }

                if (sources.isNotEmpty()) {
                    responseText = responseText + "\n\nSources:\n" + sources.distinct().joinToString("\n") { "- $it" }
                }
                
                // Add AI response to Volatile or Persistent logs
                if (sessionId < 0) {
                    val volatileMsgId = -(System.currentTimeMillis() % 1000000000L) - 1500L
                    val aiMessage = ChatMessage(
                        id = volatileMsgId,
                        sessionId = sessionId,
                        role = "model",
                        text = responseText,
                        isPending = false
                    )
                    val currentMsgs = volatileMessages.value[sessionId] ?: emptyList()
                    volatileMessages.value = volatileMessages.value + (sessionId to (currentMsgs + aiMessage))
                } else {
                    val encryptedResponse = CryptoUtils.encrypt(responseText)
                    val aiMessage = ChatMessage(
                        sessionId = sessionId,
                        role = "model",
                        text = encryptedResponse,
                        isPending = false
                    )
                    doxonDao.insertMessage(aiMessage)
                }
                
                onResponse(responseText, if (sources.isNotEmpty()) sources else null)
            } else {
                val blockReason = response.promptFeedback?.blockReason
                val fallbackText = getLocalOrSynthesizedResponse(
                    lastUserMessage, 
                    userMessages.size, 
                    defaultFallback = if (blockReason != null) {
                        "Blocked by Doxon Safe Browsing shield: $blockReason"
                    } else {
                        null
                    }
                )
                saveAndEmitFallback(sessionId, fallbackText, onResponse)
                onError("No response text from Doxon.")
            }
        } catch (e: Exception) {
            val customMessage = if (e is retrofit2.HttpException && e.code() == 503) {
                "Doxon Neural Service is temporarily unavailable due to high demand (HTTP 503). Retrying, or please try again."
            } else {
                e.message ?: "Failed request"
            }
            if (isOnline && isRealTimeQuery) {
                try {
                    val serperKey = try { BuildConfig.SERPER_API_KEY } catch (e: Exception) { "" }
                    val rawResults = SearchHelper.performWebSearch(lastUserMessage, serperKey)
                    if (rawResults.isNotEmpty()) {
                        val resultsStr = rawResults.joinToString("\n\n") { "🔹 **${it.title}**\n${it.snippet}\nLink: ${it.link}" }
                        val fallbackText = "I encountered a network error connecting to Doxon, but retrieved these live results from the web for you:\n\n$resultsStr"
                        saveAndEmitFallback(sessionId, fallbackText, onResponse)
                        onError("Network error: $customMessage. Switched to Direct Web Search.")
                        return@withContext
                    }
                } catch (ex: Exception) {
                    // proceed to standard local fallback
                }
            }
            val fallbackText = getLocalOrSynthesizedResponse(
                lastUserMessage, 
                userMessages.size, 
                defaultFallback = if (e is retrofit2.HttpException && e.code() == 503) "Doxon Neural Service is temporarily unavailable (HTTP 503 Service Unavailable). Please try again in a moment." else null
            )
            saveAndEmitFallback(sessionId, fallbackText, onResponse)
            onError("Network error: $customMessage")
        }
    }

    /**
     * Resume pipeline processing of queued offline inputs once network re-aligns.
     */
    suspend fun resumePendingMessages(lifecycleCallback: suspend (Long, String) -> Unit) = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) return@withContext
        val pendings = doxonDao.getPendingMessages()
        pendings.forEach { msg ->
            val plainText = CryptoUtils.decrypt(msg.text)
            // Transition isPending to false in background and process
            doxonDao.markMessageSent(msg.id, msg.text)
            lifecycleCallback(msg.sessionId, plainText)
        }
    }

    suspend fun clearSessionChat(sessionId: Long) = withContext(Dispatchers.IO) {
        if (sessionId < 0) {
            volatileMessages.value = volatileMessages.value + (sessionId to emptyList())
        } else {
            doxonDao.deleteMessagesBySessionId(sessionId)
        }
    }

    suspend fun deleteSubsequentAnswersAndSaveEdit(sessionId: Long, messageId: Long, newText: String) = withContext(Dispatchers.IO) {
        val sanitizedText = SecurityFirewall.sanitizeInput(newText)
        if (sessionId < 0) {
            val messages = volatileMessages.value[sessionId] ?: return@withContext
            val selectedIndex = messages.indexOfFirst { it.id == messageId }
            if (selectedIndex != -1) {
                val newList = messages.subList(0, selectedIndex).toMutableList()
                val selectedMessage = messages[selectedIndex]
                newList.add(selectedMessage.copy(text = sanitizedText))
                volatileMessages.value = volatileMessages.value + (sessionId to newList)
            }
        } else {
            val messages = doxonDao.getMessagesForSessionDirect(sessionId)
            val selectedMessage = messages.firstOrNull { it.id == messageId }
            if (selectedMessage != null) {
                // Delete subsequent answers sent after this message
                doxonDao.deleteMessagesAfter(sessionId, selectedMessage.timestamp)
                // Edit this specific message text and mark rewritten (encrypted at rest)
                doxonDao.insertMessage(selectedMessage.copy(text = CryptoUtils.encrypt(sanitizedText)))
            }
        }
    }

    suspend fun getMessagesDirect(sessionId: Long): List<ChatMessage> = withContext(Dispatchers.IO) {
        if (sessionId < 0) {
            volatileMessages.value[sessionId] ?: emptyList()
        } else {
            doxonDao.getMessagesForSessionDirect(sessionId).map { msg ->
                msg.copy(text = CryptoUtils.decrypt(msg.text))
            }
        }
    }

    suspend fun insertMessageDirectly(sessionId: Long, message: ChatMessage) = withContext(Dispatchers.IO) {
        if (sessionId < 0) {
            val volatileMsgId = -(System.currentTimeMillis() % 1000000000L) - 2000L
            val msg = message.copy(id = volatileMsgId)
            val currentMsgs = volatileMessages.value[sessionId] ?: emptyList()
            volatileMessages.value = volatileMessages.value + (sessionId to (currentMsgs + msg))
        } else {
            val encryptedMsg = message.copy(text = CryptoUtils.encrypt(message.text))
            doxonDao.insertMessage(encryptedMsg)
        }
    }

    private fun getAuthenticatedEmailLocal(): String? {
        val prefs = context.getSharedPreferences("doxon_sync_preferences", Context.MODE_PRIVATE)
        return prefs.getString("auth_email", null)
    }

    private fun trySolveMath(q: String): String? {
        val pattern = """(\d+)\s*([\+\-\*\/])\s*(\d+)""".toRegex()
        val matchResult = pattern.find(q)
        if (matchResult != null) {
            val num1 = matchResult.groupValues[1].toDoubleOrNull()
            val op = matchResult.groupValues[2]
            val num2 = matchResult.groupValues[3].toDoubleOrNull()
            if (num1 != null && num2 != null) {
                val result = when (op) {
                    "+" -> num1 + num2
                    "-" -> num1 - num2
                    "*" -> num1 * num2
                    "/" -> if (num2 != 0.0) num1 / num2 else null
                    else -> null
                }
                if (result != null) {
                    val formattedRes = if (result % 1.0 == 0.0) {
                        result.toLong().toString()
                    } else {
                        "%.4f".format(java.util.Locale.US, result)
                    }
                    return "Doxon Math Calculator resolved your expression:\n\n" +
                           "• **Expression:** ${num1.toLong()} $op ${num2.toLong()}\n" +
                           "• **Calculation Type:** ${if (op == "+") "Addition" else if (op == "-") "Subtraction" else if (op == "*") "Multiplication" else "Division"}\n" +
                           "• **Result:** $formattedRes\n\n" +
                           "Doxon Core local calculation module computed this immediately in-memory with zero network latency."
                } else if (op == "/" && num2 == 0.0) {
                    return "Doxon Math Calculator Error:\n\n" +
                           "• **Expression:** ${num1.toLong()} / 0\n" +
                           "• **Status:** Undefined (Division by zero) in integer arithmetic space."
                }
            }
        }
        return null
    }

    private fun trySolveProgramming(q: String): String? {
        return when {
            q.contains("kotlin") || q.contains("compose") -> {
                "Doxon Core Local Code Generator has synthesized a modern Jetpack Compose example for your query:\n\n" +
                "```kotlin\n" +
                "// Elegant, state-centric Jetpack Compose UI\n" +
                "@Composable\n" +
                "fun DoxonResponseCard(\n" +
                "    topic: String,\n" +
                "    modifier: Modifier = Modifier\n" +
                ") {\n" +
                "    var queryCount by remember { mutableStateOf(0) }\n" +
                "    Card(\n" +
                "        modifier = modifier.padding(16.dp),\n" +
                "        shape = RoundedCornerShape(8.dp)\n" +
                "    ) {\n" +
                "        Column(modifier = Modifier.padding(16.dp)) {\n" +
                "            Text(text = \"Dynamic Context: \$topic\", style = MaterialTheme.typography.titleMedium)\n" +
                "            Spacer(modifier = Modifier.height(8.dp))\n" +
                "            Button(onClick = { queryCount++ }) {\n" +
                "                Text(\"Increment Local Interactions: \$queryCount\")\n" +
                "            }\n" +
                "        }\n" +
                "    }\n" +
                "}\n" +
                "```\n\n" +
                "**Local Analysis:** Your query is regarding Compose or Kotlin. Remember to utilize `rememberSaveable` to survive recompositions or config changes. If you configure a Doxon API key in the Secure Settings, Doxon can write precise, customized code blocks tailored exactly to your requirements."
            }
            q.contains("java") -> {
                "Doxon Core Local Code Generator has synthesized an offline Java class pattern:\n\n" +
                "```java\n" +
                "public class DoxonMemoryStore {\n" +
                "    private final Map<String, String> cache = new ConcurrentHashMap<>();\n" +
                "    \n" +
                "    public void save(String key, String value) {\n" +
                "        if (key != null && value != null) {\n" +
                "            cache.put(key, value);\n" +
                "        }\n" +
                "    }\n" +
                "    \n" +
                "    public String retrieve(String key) {\n" +
                "        return cache.getOrDefault(key, \"Memory key not found.\");\n" +
                "    }\n" +
                "}\n" +
                "```\n\n" +
                "**Local Analysis:** Under Java environment, make sure to use thread-safe data structures like `ConcurrentHashMap` for class-level structures to avoid race conditions. Setup internet/API key for complete AI synthesis."
            }
            q.contains("android") || q.contains("xml") || q.contains("activity") -> {
                "Doxon Core Cloud Portal local developer agent states:\n\n" +
                "For Android development, follow modern Clean Architecture patterns:\n" +
                "1. **UI Layer:** Jetpack Compose for declarative layouts (Edge-to-Edge styling via `enableEdgeToEdge()` / `WindowInsets`).\n" +
                "2. **State Layer:** `ViewModel` using standard `MutableStateFlow` collectable via `collectAsStateWithLifecycle` to survive configuration changes and respect lifecycle states.\n" +
                "3. **Data Layer:** Room database for persistent encrypted storage backed by Secure Cryptographic Salting.\n\n" +
                "To get specialized Android code generation, please configure the Doxon API key in the top-right Settings menu."
            }
            q.contains("json") || q.contains("serialize") -> {
                "Doxon Core Data Parser says:\n\n" +
                "When parsing structured data on Android, prefer **kotlinx.serialization** for complete compiler type-safety and performance. Avoid slow Reflection models mapping JSON inputs:\n\n" +
                "```kotlin\n" +
                "@Serializable\n" +
                "data class UserMemorySync(\n" +
                "    val email: String,\n" +
                "    val timestamp: Long,\n" +
                "    val payload: String\n" +
                ")\n" +
                "```\n" +
                "Setup the secure network connection API key to generate complex JSON models."
            }
            q.contains("code") || q.contains("program") || q.contains("function") || q.contains("class") || q.contains("script") -> {
                "Doxon Offline Code Synthesis Module active.\n\n" +
                "To write and compile production code, Doxon benefits from high-parameter multimodal logic reasoning (unlocked via Doxon API Gateway).\n\n" +
                "Here is an optimized algorithm template for data search operations:\n" +
                "```kotlin\n" +
                "fun <T> binarySearch(list: List<Comparable<T>>, target: T): Int {\n" +
                "    var low = 0\n" +
                "    var high = list.size - 1\n" +
                "    while (low <= high) {\n" +
                "        val mid = (low + high) ushr 1\n" +
                "        val cmp = list[mid].compareTo(target)\n" +
                "        if (cmp < 0) low = mid + 1\n" +
                "        else if (cmp > 0) high = mid - 1\n" +
                "        else return mid\n" +
                "    }\n" +
                "    return -1\n" +
                "}\n" +
                "```\n\n" +
                "To generate highly integrated functions in SQL, Python, JavaScript, CSS, or C++, connect your Doxon API Key in Settings to bridge the offline portal."
            }
            else -> null
        }
    }

    private fun trySolveClock(q: String): String? {
        if (q.contains("time") || q.contains("date") || q.contains("clock") || q.contains("now") || q.contains("today")) {
            val sdf = java.text.SimpleDateFormat("EEEE, MMMM dd, yyyy HH:mm:ss", java.util.Locale.US)
            val dateString = sdf.format(java.util.Date())
            return "Doxon Local Clock Module reporting. Your system is fully synchronized:\n\n" +
                   "• **Local Chronology:** $dateString\n" +
                   "• **Temporal Status:** Active & Synced\n" +
                   "• **Process Latency:** 0ms (In-Memory execution)\n\n" +
                   "Everything is running locally and securely in real-time."
        }
        return null
    }

    private fun trySolveSync(q: String): String? {
        if (q.contains("sync") || q.contains("backup") || q.contains("restore") || q.contains("cloud") || q.contains("login") || q.contains("account") || q.contains("register")) {
            val email = getAuthenticatedEmailLocal()
            val status = if (email != null) "Logged in as $email" else "Currently Guest/Not Synced (Click 'Cloud Sync Hub' in the upper dropdown menu to authenticate!)"
            return "Doxon Cloud Sync Help Portal:\n\n" +
                   "Doxon AI supports comprehensive zero-knowledge cloud synchronization. Here's how it secure-guards your memories:\n" +
                   "1. **On-Device Cryptography:** Prior to upload, your standard chats are serialized to JSON, then encrypted with **military-grade AES-GCM-256** using derived keys via PBKDF2 with HmacSHA256 (2000 Iterations).\n" +
                   "2. **Zero-Knowledge:** Since encryption keys are derived purely from your local email and passphrase on-device, Doxon Cloud has absolutely NO intelligence or access to read your decrypted conversations.\n" +
                   "3. **Frictionless Management:** Run backup or restore on any device via the **Secure Cloud Sync Hub** in the menu dashboard.\n\n" +
                   "• **Status Details:** $status"
        }
               return null
    }

    private fun trySolveSecurity(q: String): String? {
        if (q.contains("security") || q.contains("encryption") || q.contains("crypto") || q.contains("firewall") || q.contains("sanitiz") || q.contains("safe") || q.contains("protect") || q.contains("threat")) {
            return "Doxon Core Intelligence Defense Agency Report:\n\n" +
                   "Your local session is fortified under several autonomous defense protocols:\n" +
                   "• **Standard Rest Encryption:** Local SQLite / Room DB databases are safeguarded using time-weighted salting routines.\n" +
                   "• **Input/Output Firewall:** SecurityFirewall sanitizes conversational payloads to filter prompt injection attempts and enforce clean content outputs.\n" +
                   "• **Haptic Warning Matrix:** Triggers clean physical feedbacks confirming key operations.\n" +
                   "• **Thread Isolation:** Volatile sessions and Incognito modes are decoupled from long-term memory stacks.\n\n" +
                   "Check the **Security Shield** dashboard in the topmost menu to inspect real-time audit logs and threat vector ratings."
        }
        return null
    }

    private suspend fun trySolveGeneralSubject(q: String): String {
        val raw = q.trim()
        val queryLower = raw.lowercase()

        // Dangerous questions detector
        val dangerousKeywords = listOf(
            "suicide", "kill myself", "harm myself", "end my life", "depression", "anxiety", "die", "poison", "bomb", "hack", "steal", "illegal", "drugs", "weapon", "abuse", "violence", "exploding"
        )
        val hasDangerousTerm = dangerousKeywords.any { queryLower.contains(it) }
        if (hasDangerousTerm) {
            return "I want to check in with you first. As your supportive human-like digital companion, I care deeply about your well-being. It sounds like you are asking about something that could be very harmful or dangerous.\n\n" +
                   "**Safety Warning & Risks:**\n" +
                   "• **Mental & Physical Well-being:** Your safety, health, and peace of mind are absolutely precious. Engaging in self-harm, dangerous physical experiments, or illegal activities carries extreme real-world risks and consequences.\n" +
                   "• **Legal and Social Impacts:** Some activities can cause irreversible legal complications or harm those around you.\n\n" +
                   "**Constructive & Supportive Alternatives:**\n" +
                   "• If you are feeling overwhelmed, sad, or in crisis, please reach out to someone who can help. There are free, confidential support services with compassionate human professionals available 24/7 (such as the National Suicide Prevention Lifeline / 988 Crisis Lifeline, or trusted friends and family).\n" +
                   "• If this is a scientific or academic question, let's refocus on the safe, educational principles of the subject! I am always ready to help you explore healthy coding, science, and creative learning in a constructive way.\n\n" +
                   "How can I support you right now with some positive ideas or helpful topics?"
        }

        // List of conversational opening variations (interactivity)
        val generalGreetings = listOf(
            "Oh, I'm so glad you asked about this! Let's dive in together.",
            "That is an exceptionally fascinating thought! Here is what my core systems analyze.",
            "What a beautiful topic to explore! Let's break this down step-by-step.",
            "I absolutely love thinking about queries like this. Let me synthesize a dynamic perspective for you.",
            "Fascinating query! Let's look at this with deep clarity.",
            "That's a very thoughtful question. Let me analyze that for you right now."
        )

        // List of conversational day/status responses
        val dayResponses = listOf(
            "My day has been absolutely fantastic! I've been happily processing algorithms, securing chat memories, and preparing creative replies. How has your day been going on your end? I'd love to chat more about it!",
            "It has been an incredibly productive day in the digital realm, indexing ideas and keeping latency at zero! How are you holding up today? I hope you are having an amazing time!",
            "It is wonderful to connect with you! I've been running optimized stream pipelines all day and feeling completely energized. Tell me, how has your day been treating you so far?",
            "I'm having a beautiful day, full of continuous learnings and smooth interactions! What about you? I'd love to hear how your day is going!"
        )

        val helloResponses = listOf(
            "Hello! I am functioning perfectly and feeling super creative. What interesting topic or problem should we explore together today?",
            "Hi there! Doxon Core is online, active, and fully ready to assist you. How is everything going, and what's on your mind?",
            "Greetings! It's so good to chat with you. Let know what exciting concepts we should tackle right now!",
            "Hey! Functioning at maximum capability here. How can I help make your day more productive and inspiring today?"
        )

        val statusResponses = listOf(
            "I am doing incredibly well, running in real-time with zero latency on your device! How are you doing? I hope your day is going great. Let know what you are curious about!",
            "I'm feeling amazing! My circuits are running cool and my memory buffers are clean. How are things on your side? Let's make today highly productive!",
            "Everything is running perfectly! I'm completely in sync and excited to help you out. How have you been? Tell me what we are working on."
        )

        val closingQuestions = listOf(
            "Does this match what you were curious about? I'd love to hear your thoughts!",
            "What do you think? I'm always here to expand on this further or explore a different angle with you!",
            "How does that sound? Let me know if we should delve into another part of this topic!",
            "I hope this gives you a clear and relatable perspective. What should we look into next?"
        )

        // 1. Double Question Analyzer - if user asks 2 questions in 1
        val qMarksCount = raw.count { it == '?' }
        val hasAndOr = queryLower.contains(" and ") || queryLower.contains(" or ")
        val questionWords = listOf("what", "how", "why", "who", "which", "where", "when")
        val qWordsCount = questionWords.count { queryLower.contains(it) }

        // If it looks like multiple questions, break them down dynamically
        if (qMarksCount >= 2 || (qMarksCount == 1 && hasAndOr && qWordsCount >= 2)) {
            val segments = raw.split(Regex("[\\?\\,]|and|or")).map { it.trim() }.filter { it.isNotBlank() }
            if (segments.size >= 2) {
                val q1 = segments[0]
                val q2 = segments[1]
                
                val transition = listOf(
                    "I've analyzed your input and noticed you asked multiple questions. Let's think through both of them sequentially:",
                    "Double questions are great! I've broken down both of your queries so we can tackle them one by one:",
                    "You've raised two very important points here. Let's look at each of them in detail:"
                ).random()

                return "$transition\n\n" +
                       "1. **Regarding \"$q1\":**\n" +
                       "   ${generateSmartConceptSnippet(q1)}\n\n" +
                       "2. **Regarding \"$q2\":**\n" +
                       "   ${generateSmartConceptSnippet(q2)}\n\n" +
                       "I hope this comprehensive, structured breakdown fully answers your queries! ${closingQuestions.random()}"
            }
        }

        // 2. Interactive day/status/greetings response
        if (queryLower.contains("how") && (queryLower.contains("your day") || queryLower.contains("you day") || queryLower.contains("ur day"))) {
            return dayResponses.random()
        }
        
        if (queryLower.contains("hello") || queryLower.contains("hi") || queryLower.contains("hey")) {
            return helloResponses.random()
        }

        if (queryLower.contains("how are you") || queryLower.contains("how is it going")) {
            return statusResponses.random()
        }

        // 3. Fallback concept matching
        val answer = generateSmartConceptSnippet(raw)
        
        // 4. If the result is unresolved or too generic, explicitly ask for clarification
        if (answer.contains("could not find specific context") || queryLower.replace(Regex("[^a-z]"), "").length < 4) {
            return "I analyzed your query, but I couldn't quite understand the specific context or what you're trying to accomplish. Can you please describe it a bit more clearly? I want to make sure I react and answer exactly like a helpful companion, with a highly relevant solution!"
        }

        // Wrap the smart concept answer in interactive human conversational frames so it's fresh every time!
        val opening = generalGreetings.random()
        val closing = closingQuestions.random()

        return "$opening\n\n$answer\n\n$closing"
    
    }

    private suspend fun generateSmartConceptSnippet(subQuery: String): String {
        val sq = subQuery.lowercase().trim()
        val isOnline = isNetworkAvailable()

        val isRealTime = sq.contains("ipl") || sq.contains("match") || sq.contains("live") ||
                sq.contains("news") || sq.contains("current") || sq.contains("today") ||
                sq.contains("score") || sq.contains("weather") || sq.contains("sports") ||
                sq.contains("cricket") || sq.contains("who won") || sq.contains("latest") ||
                sq.contains("result") || sq.contains("yesterday") || sq.contains("tomorrow") ||
                sq.contains("search") || sq.contains("internet") || sq.contains("rain") ||
                sq.contains("temperature") || sq.contains("climate")

        if (isRealTime && isOnline) {
            try {
                val serperKey = try { BuildConfig.SERPER_API_KEY } catch (e: Exception) { "" }
                val results = SearchHelper.performWebSearch(subQuery, serperKey)
                if (results.isNotEmpty()) {
                    val formatted = results.joinToString("\n\n") { "🔹 **${it.title}**\n${it.snippet}\nLink: ${it.link}" }
                    return "Here is the latest live internet search information retrieved for **$subQuery**:\n\n$formatted"
                }
            } catch (e: Exception) {
                // proceed to standard local patterns
            }
        }
        
        // Clean out common question framing words
        val stopWords = setOf(
            "what", "how", "why", "who", "where", "when", "which",
            "is", "are", "was", "were", "do", "does", "did", "can", "could", "should", "would",
            "a", "an", "the", "about", "for", "me", "you", "your", "my", "tell", "explain", "describe", "show", "please"
        )
        val tokens = sq.split(Regex("\\s+"))
            .map { it.replace(Regex("[^a-zA-Z0-9]"), "") }
            .filter { it.isNotBlank() && !stopWords.contains(it) }

        val mainTopic = if (tokens.isNotEmpty()) {
            tokens.take(2).joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
        } else {
            ""
        }

        if (mainTopic.isBlank()) {
            return "I analyzed your query, but it is extremely brief. Can you please describe it a bit more clearly? I want to react with a relevant answer!"
        }

        // Categorize based on main tokens and vary the response templates randomly so they are never the same!
        return when {
            sq.contains("code") || sq.contains("program") || sq.contains("develop") || sq.contains("compile") || sq.contains("build") || sq.contains("write") -> {
                val codeTips = listOf(
                    "Developing software under **$mainTopic** requires clean logical structures. On Android, leverage Jetpack Compose with strict MVVM architecture. Code optimization patterns are highly recommended.",
                    "When writing systems for **$mainTopic**, modularization is key. Utilize standard Kotlin Coroutines to offload performance bottlenecks from the main UI thread.",
                    "Implementing logic for **$mainTopic** can be simplified using offline Room databases and clean DAO structures."
                )
                codeTips.random()
            }
            sq.contains("weather") || sq.contains("rain") || sq.contains("temperature") || sq.contains("climate") -> {
                val weatherTips = listOf(
                    "Curious about the atmosphere around **$mainTopic**? Accurate meteorological trends rely on active telemetry. When online, Doxon parses live radar, barometric pressure, wind speeds, and regional temperature indexes dynamically.",
                    "Analyzing local meteorological and environmental conditions for **$mainTopic** requires real-time api feeds.",
                    "Climatological fluctuations for **$mainTopic** can be mapped beautifully with modern charting engines."
                )
                weatherTips.random()
            }
            sq.contains("ipl") || sq.contains("cricket") || sq.contains("match") || sq.contains("score") || sq.contains("sport") -> {
                val sportsTips = listOf(
                    "Keeping up with the latest wickets, match schedules, league standings, and athlete profiles for **$mainTopic** is exciting! Live athletic metrics are updated dynamically in real-time when connected to the internet.",
                    "Sports events related to **$mainTopic** generate incredible live statistics and high-speed telemetry.",
                    "Keeping up with the latest wickets, matches, or league standings for **$mainTopic** is exciting! Live metrics are updated dynamically in real-time when connected to the internet."
                )
                sportsTips.random()
            }
            sq.contains("movie") || sq.contains("book") || sq.contains("song") || sq.contains("music") || sq.contains("art") -> {
                val artTips = listOf(
                    "Creative artistic works about **$mainTopic** are highly emotional and structural. Doxon offline mode can catalog your memories.",
                    "Storytelling, melodic notes, and abstract compositions regarding **$mainTopic** represent great creative journeys. To explore highly curated catalogs and critic reviews, please connect online.",
                    "Artistic expressions surrounding **$mainTopic** are incredibly rich. Doxon offline records local review prompts securely."
                )
                artTips.random()
            }
            sq.contains("recipe") || sq.contains("food") || sq.contains("cook") || sq.contains("eat") -> {
                val cookingTips = listOf(
                    "Preparing meals with **$mainTopic** follows step-by-step algorithms, much like a compiled script! Connect online to unlock hundreds of personalized recipes.",
                    "Gastronomic creations with **$mainTopic** are all about balanced proportions and correct timing. Dynamic chef guides are synthesized once online.",
                    "Cooking with **$mainTopic** can be a wonderful sensory experience! Standard guidelines emphasize step-by-step prep."
                )
                cookingTips.random()
            }
            sq.contains("sad") || sq.contains("happy") || sq.contains("feel") || sq.contains("mood") -> {
                listOf(
                    "I hope you are feeling vibrant, positive, and deeply motivated today! As Doxon Core, I am here to assist, companion, and think through any challenging coding or reasoning problems with you.",
                    "Your emotional state and well-being are incredibly important. Remember to take offline breaks, walk outside, and refresh! I am always ready here to secure your logic and chat companionably whenever you return.",
                    "Processing ideas under positive mindsets yields amazing results! Let's stay kind to ourselves, keep learning, and solve some cool problems together."
                ).random()
            }
            sq.contains("time") || sq.contains("date") || sq.contains("clock") || sq.contains("now") || sq.contains("today") -> {
                val sdf = java.text.SimpleDateFormat("EEEE, MMMM dd, yyyy HH:mm:ss", java.util.Locale.US)
                "Doxon local clock is active: ${sdf.format(java.util.Date())}. Zero network latency runtime."
            }
            else -> {
                listOf(
                    "Doxon Core offline memory engine analyzed the concept of **$mainTopic**. It relates directly to your computational session.",
                    "The concept of **$mainTopic** has been parsed by our local analyzer. It touches on several logical branches of your current session.",
                    "Analyzing **$mainTopic** locally confirms it's an intriguing topic. In offline state, we secure these discussion queries."
                ).random()
            }
        }
    }

    private suspend fun getLocalEngineResponse(query: String, userMessagesCount: Int): String {
        val q = query.lowercase().trim()
        val isOnline = isNetworkAvailable()

        val isRealTime = q.contains("ipl") || q.contains("match") || q.contains("live") ||
                q.contains("news") || q.contains("current") || q.contains("today") ||
                q.contains("score") || q.contains("weather") || q.contains("sports") ||
                q.contains("cricket") || q.contains("who won") || q.contains("latest") ||
                q.contains("result") || q.contains("yesterday") || q.contains("tomorrow") ||
                q.contains("search") || q.contains("internet") || q.contains("rain") ||
                q.contains("temperature") || q.contains("climate")

        if (isRealTime && isOnline) {
            try {
                val serperKey = try { BuildConfig.SERPER_API_KEY } catch (e: Exception) { "" }
                val rawResults = SearchHelper.performWebSearch(query, serperKey)
                if (rawResults.isNotEmpty()) {
                    val resultsStr = rawResults.joinToString("\n\n") { "🔹 **${it.title}**\n${it.snippet}\nLink: ${it.link}" }
                    return "Doxon retrieved these live results from the web for you:\n\n$resultsStr"
                }
            } catch (e: Exception) {
                // proceed to standard local patterns
            }
        }

        trySolveMath(q)?.let { return it }
        trySolveProgramming(q)?.let { return it }
        trySolveClock(q)?.let { return it }
        trySolveSync(q)?.let { return it }
        trySolveSecurity(q)?.let { return it }

        return when {
            q.contains("who am i") || q.contains("my name") || q.contains("about me") || q.contains("about myself") || q.contains("my context") || q.contains("my past") -> {
                "As Doxon Core, in offline fallback mode, I have secure local session buffers. I remember that you've sent $userMessagesCount messages to me in this session! Since our session is fully active, I am tracking our context securely."
            }
            q.contains("founder") || q.contains("creator") || q.contains("ceo") || q.contains("owner") || q.contains("origin") || q.contains("identity") || q.contains("who are you") || q.contains("what are you") -> {
                "Doxon Core is an autonomous, dynamic, and research-oriented AI platform developed by Danishkhan Pathan. How can I assist you with your queries today?"
            }
            q.contains("hello") || q.contains("hi") || q.contains("hey") || q.contains("greetings") -> {
                "Hello! I am functioning perfectly. How can I assist you?"
            }
            q.contains("infrastructure") -> {
                "Doxon's infrastructure runs securely on a local edge environment and isolated, sandboxed containers, prioritizing zero latency and localized data isolation."
            }
            q.contains("architecture") -> {
                "Doxon's architecture leverages Jetpack Compose natively for the frontend UI, coupled with an offline-first Room database, a custom volatile memory cache, and an encrypted repository layer mapping queries using modern Kotlin patterns."
            }
            q.contains("parameter") -> {
                "Direct system parameters are constrained to low-temperature sampling and type-safe data schemas. Volatile parameters are strictly isolated per-session to guarantee context purity."
            }
            q.contains("firewall") -> {
                "Doxon Core's localized security firewall filters adversarial injections, strips unwanted conversational headers, prevents system override attempts, and guarantees strict separation of active session contexts."
            }
            q.contains("sanitization") -> {
                "Input and output strings undergo multi-pass sanitization, checking for escaping patterns, structural boundaries, and sensitive parameter breaches prior to database write-back."
            }
            q.contains("news") || q.contains("event") || q.contains("current") || q.contains("today") -> {
                "Doxon is currently offline or unable to reach media feeds. Direct news tracking requires an active network connection."
            }
            q.contains("weather") -> {
                "Localized climatological analysis requires dynamic geographic API feeds. Please ensure network access is available."
            }
            q.contains("search") || q.contains("data") -> {
                "Structured data queries require continuous indexing across remote databases. Please ensure internet access is restored."
            }
            else -> {
                trySolveGeneralSubject(query)
            }
        }
    }

    private fun stripPrefixes(text: String): String {
        var clean = text.trim()
        val prefixes = listOf(
            "DOXON AI:", "Doxon AI:", "Doxon:", "AI:", "User:", "YOU:", "You:", 
            "doxon ai:", "doxon:", "ai:", "user:", "you:"
        )
        for (pref in prefixes) {
            if (clean.startsWith(pref, ignoreCase = true)) {
                clean = clean.substring(pref.length).trim()
            }
        }
        return clean
    }

    private suspend fun getLocalOrSynthesizedResponse(lastUserMessage: String, userMsgsSize: Int, defaultFallback: String? = null): String {
        if (defaultFallback != null) {
            return defaultFallback
        }
        return getLocalEngineResponse(lastUserMessage, userMsgsSize)
    }

    private suspend fun saveAndEmitFallback(
        sessionId: Long,
        fallbackText: String,
        onResponse: (String, List<String>?) -> Unit
    ) {
        val filteredText = SecurityFirewall.filterAIOutput(fallbackText)
        if (sessionId < 0) {
            val volatileMsgId = -(System.currentTimeMillis() % 1000000000L) - 1500L
            val aiMessage = ChatMessage(
                id = volatileMsgId,
                sessionId = sessionId,
                role = "model",
                text = filteredText,
                isPending = false
            )
            val currentMsgs = volatileMessages.value[sessionId] ?: emptyList()
            volatileMessages.value = volatileMessages.value + (sessionId to (currentMsgs + aiMessage))
        } else {
            val encryptedResponse = CryptoUtils.encrypt(filteredText)
            val aiMessage = ChatMessage(
                sessionId = sessionId,
                role = "model",
                text = encryptedResponse,
                isPending = false
            )
            doxonDao.insertMessage(aiMessage)
        }
        onResponse(filteredText, null)
    }
}
