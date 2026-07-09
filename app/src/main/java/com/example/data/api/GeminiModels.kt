package com.example.data.api

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GenerateContentRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig? = null,
    val tools: List<Tool>? = null,
    val safetySettings: List<SafetySetting>? = null,
    val systemInstruction: Content? = null
)

@JsonClass(generateAdapter = true)
data class Content(
    val role: String? = null,
    val parts: List<Part>
)

@JsonClass(generateAdapter = true)
data class Part(
    val text: String? = null,
    val inlineData: InlineData? = null
)

@JsonClass(generateAdapter = true)
data class InlineData(
    val mimeType: String,
    val data: String
)

@JsonClass(generateAdapter = true)
data class Tool(
    val googleSearch: GoogleSearch? = null,
    val googleSearchRetrieval: GoogleSearchRetrieval? = null,
    val googleMaps: GoogleMaps? = null
)

@JsonClass(generateAdapter = true)
class GoogleSearch

@JsonClass(generateAdapter = true)
class GoogleMaps

@JsonClass(generateAdapter = true)
data class GoogleSearchRetrieval(
    val dummy: String? = null
)

@JsonClass(generateAdapter = true)
data class SafetySetting(
    val category: String,
    val threshold: String
)

@JsonClass(generateAdapter = true)
data class GenerationConfig(
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val maxOutputTokens: Int? = null,
    val thinkingConfig: ThinkingConfig? = null,
    val imageConfig: ImageConfig? = null,
    val responseModalities: List<String>? = null,
    val speechConfig: SpeechConfig? = null
)

@JsonClass(generateAdapter = true)
data class ThinkingConfig(
    val thinkingLevel: String
)

@JsonClass(generateAdapter = true)
data class ImageConfig(
    val aspectRatio: String,
    val imageSize: String
)

@JsonClass(generateAdapter = true)
data class SpeechConfig(
    val voiceConfig: VoiceConfig
)

@JsonClass(generateAdapter = true)
data class VoiceConfig(
    val prebuiltVoiceConfig: PrebuiltVoiceConfig
)

@JsonClass(generateAdapter = true)
data class PrebuiltVoiceConfig(
    val voiceName: String
)

@JsonClass(generateAdapter = true)
data class GenerateContentResponse(
    val candidates: List<Candidate>? = null,
    val promptFeedback: PromptFeedback? = null
)

@JsonClass(generateAdapter = true)
data class Candidate(
    val content: Content? = null,
    val finishReason: String? = null,
    val safetyRatings: List<SafetyRating>? = null,
    val groundingMetadata: GroundingMetadata? = null
)

@JsonClass(generateAdapter = true)
data class SafetyRating(
    val category: String,
    val probability: String,
    val blocked: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class GroundingMetadata(
    val webSearchQueries: List<String>? = null,
    val groundingChunks: List<GroundingChunk>? = null
)

@JsonClass(generateAdapter = true)
data class GroundingChunk(
    val web: WebSource? = null
)

@JsonClass(generateAdapter = true)
data class WebSource(
    val uri: String? = null,
    val title: String? = null
)

@JsonClass(generateAdapter = true)
data class PromptFeedback(
    val blockReason: String? = null
)
