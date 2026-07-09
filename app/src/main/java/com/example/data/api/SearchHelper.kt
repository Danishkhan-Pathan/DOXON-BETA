package com.example.data.api

import android.text.Html
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SearchResult(
    val title: String,
    val link: String,
    val snippet: String
)

object SearchHelper {
    private const val TAG = "SearchHelper"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    /**
     * Executes web search. First tries Serper.dev if API key is provided,
     * otherwise falls back to DuckDuckGo HTML scraping.
     */
    suspend fun performWebSearch(query: String, serperApiKey: String?): List<SearchResult> = withContext(Dispatchers.IO) {
        val apiKey = serperApiKey?.trim()
        if (!apiKey.isNullOrEmpty() && apiKey != "MY_SERPER_API_KEY" && apiKey != "null") {
            Log.d(TAG, "Using Serper.dev for search query: $query")
            val results = searchSerper(query, apiKey)
            if (results.isNotEmpty()) {
                return@withContext results
            }
        }
        
        Log.d(TAG, "Using DuckDuckGo HTML fallback for search query: $query")
        return@withContext searchDuckDuckGo(query)
    }

    private suspend fun searchSerper(query: String, apiKey: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SearchResult>()
        try {
            val jsonPayload = JSONObject().apply {
                put("q", query)
            }
            
            val body = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            
            val request = Request.Builder()
                .url("https://google.serper.dev/search")
                .header("X-API-KEY", apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build()
                
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val jsonStr = response.body?.string() ?: ""
                    val jsonObj = JSONObject(jsonStr)
                    if (jsonObj.has("organic")) {
                        val organicArray = jsonObj.getJSONArray("organic")
                        for (i in 0 until organicArray.length()) {
                            if (results.size >= 5) break
                            val item = organicArray.getJSONObject(i)
                            val title = item.optString("title", "")
                            val link = item.optString("link", "")
                            val snippet = item.optString("snippet", "")
                            if (title.isNotEmpty()) {
                                results.add(SearchResult(title, link, snippet))
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "Serper.dev returned response code: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Serper.dev search failed", e)
        }
        return@withContext results
    }

    private suspend fun searchDuckDuckGo(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SearchResult>()
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://html.duckduckgo.com/html/?q=$encodedQuery"
            
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    val resultBlocks = html.split("<div class=\"result")
                    for (i in 1 until resultBlocks.size) {
                        if (results.size >= 5) break
                        val block = resultBlocks[i]
                        
                        // Regular expressions to extract link, title, and snippet
                        val hrefRegex = """class="result__a"\s+href="([^"]+)"""".toRegex()
                        val titleRegex = """class="result__a"[^>]*>([^<]+)""".toRegex()
                        val snippetRegex = """class="result__snippet"[^>]*>([^<]+)""".toRegex()
                        
                        val hrefMatch = hrefRegex.find(block)
                        val titleMatch = titleRegex.find(block)
                        val snippetMatch = snippetRegex.find(block)
                        
                        if (hrefMatch != null && titleMatch != null) {
                            var link = hrefMatch.groupValues[1]
                            if (link.contains("uddg=")) {
                                val param = link.substringAfter("uddg=")
                                link = URLDecoder.decode(param.substringBefore("&"), "UTF-8")
                            }
                            
                            val titleRaw = titleMatch.groupValues[1]
                            val title = decodeHtml(titleRaw)
                            
                            val snippet = if (snippetMatch != null) {
                                decodeHtml(snippetMatch.groupValues[1])
                            } else {
                                ""
                            }
                            
                            if (title.isNotEmpty()) {
                                results.add(SearchResult(title, link, snippet))
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "DuckDuckGo HTML query failed with response code: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "DuckDuckGo search failed", e)
        }
        return@withContext results
    }

    private fun decodeHtml(htmlText: String): String {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                Html.fromHtml(htmlText, Html.FROM_HTML_MODE_LEGACY).toString().trim()
            } else {
                @Suppress("DEPRECATION")
                Html.fromHtml(htmlText).toString().trim()
            }
        } catch (e: Exception) {
            htmlText.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#39;", "'")
                .trim()
        }
    }
}
