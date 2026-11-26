package com.voxfeed.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Summary depth; persisted as its name in preferences and used as the cache key with the videoId. */
enum class SummaryDepth(val label: String, val maxTranscriptChars: Int) {
    BRIEF("BRIEF", 15_000),
    STANDARD("STANDARD", 30_000),
    DEEP("DEEP", 45_000);

    companion object {
        fun from(name: String?) = if (name == "DEEP_DOSSIER") DEEP else entries.firstOrNull { it.name == name } ?: BRIEF
    }
}

/** Outcome of a Gemini summary request. */
sealed interface DigestResult {
    data class Success(val digest: String) : DigestResult
    data class QuotaExceeded(val googleMessage: String = "") : DigestResult
    object InvalidKey : DigestResult
    data class ServiceCongested(val trace: String = "") : DigestResult
    data class NetworkOrApiError(val detail: String) : DigestResult
}

/** User-facing text for a failed [DigestResult]. */
fun DigestResult.failureMessage(): String = when (this) {
    is DigestResult.QuotaExceeded ->
        if (googleMessage.isNotBlank()) "Quota exceeded (error 429): $googleMessage"
        else "Quota exceeded (error 429). Wait for your quota to reset or add a different API key in Settings."
    is DigestResult.InvalidKey ->
        "Invalid API key. Check your Gemini API key in Settings."
    is DigestResult.ServiceCongested ->
        "The Gemini service is busy (error 503). Try again shortly.$trace"
    is DigestResult.NetworkOrApiError -> "Request failed: $detail"
    is DigestResult.Success -> ""
}

object AiSummaryManager {
    /** Tight, highest-availability-first candidate list. No dynamic discovery, so failures resolve quickly. */
    private val CANDIDATES = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.6-flash", "gemini-3.8-flash")
    private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val STAMP_RULES =
        "The transcript contains timestamp markers like [MM:SS] or [H:MM:SS] at the start of lines. " +
            "Cite the relevant timestamp inline, in exactly that bracketed form (one timestamp per bracket, " +
            "e.g. [03:45] or [1:02:10]), next to every key point and notable quote. Only use timestamps that " +
            "appear in the transcript; if it has none (title/description only), do not cite any. "

    private const val BRIEF_PROMPT =
        "You are a terse analyst doing TL;DR triage. Given a YouTube video transcript (or just its title and description " +
            "when no captions exist), reply in plain text with EXACTLY 3 ultra-concise bullet points, each starting with " +
            "\"- \" and at most one short sentence. Nothing else: no headings, no intro, no verdict.\n" +
            STAMP_RULES +
            "Do not use markdown formatting such as ** or #. Be factual; do not invent details."

    private const val STANDARD_PROMPT =
        "You are a sharp analyst. Given a YouTube video transcript (or just its title and description " +
            "when no captions exist), reply in plain text with EXACTLY these two sections and nothing else:\n" +
            "CORE THESIS\n(the central claim or purpose, 1-3 sentences)\n\n" +
            "KEY TAKEAWAYS\n(5 to 7 bullets, each starting with \"- \"; include a timestamp on each bullet when available)\n" +
            STAMP_RULES +
            "Do not use markdown formatting such as ** or #. Be factual; do not invent details."
    private const val DEEP_PROMPT =
        "You are a meticulous research analyst. Given a YouTube video transcript (or just its title and " +
            "description when no captions exist), reply in plain text with a comprehensive structured breakdown " +
            "using EXACTLY these sections:\n" +
            "CORE THESIS\n(the central claim or purpose, 2-4 sentences)\n\n" +
            "STRUCTURE & ARGUMENTS\n(the main arguments in the order presented, as bullets starting with \"- \")\n\n" +
            "TECHNICAL DETAILS\n(specific numbers, names, dates, methods, examples; bullets starting with \"- \")\n\n" +
            "KEY QUOTES\n(notable verbatim quotes from the transcript, each with its timestamp when available)\n\n" +
            "COUNTERPOINTS & CAVEATS\n(weaknesses, disputed claims, missing context; bullets starting with \"- \")\n\n" +
            "FINAL VERDICT\n(whether to watch in full, skim, or skip, and why)\n" +
            STAMP_RULES +
            "Do not use markdown formatting such as ** or #. Be factual; do not invent details."
    /** Fetches the transcript, queries Gemini, and on success caches the digest for this depth only. */
    suspend fun generate(
        apiKey: String, videoId: String, title: String, description: String,
        depth: SummaryDepth, dao: DigestDao
    ): DigestResult {
        if (apiKey.isBlank()) return DigestResult.InvalidKey
        val transcript = try {
            YouTubeStreamExtractor.fetchTranscriptText(videoId, title, description)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "TITLE: $title\n\nDESCRIPTION: $description"
        }
        val result = summarize(apiKey.trim(), title, transcript, depth)
        if (result is DigestResult.Success) {
            dao.upsert(DigestEntity(videoId, depth.name, result.digest, System.currentTimeMillis()))
        }
        return result
    }
    suspend fun summarize(apiKey: String, title: String, transcript: String, depth: SummaryDepth): DigestResult =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", when (depth) { SummaryDepth.BRIEF -> BRIEF_PROMPT; SummaryDepth.STANDARD -> STANDARD_PROMPT; SummaryDepth.DEEP -> DEEP_PROMPT }))))
                    .put(
                        "contents", JSONArray().put(
                            JSONObject().put("role", "user").put(
                                "parts", JSONArray().put(JSONObject().put("text", "VIDEO TITLE: $title\n\nTRANSCRIPT:\n${condense(transcript, depth)}"))
                            )
                        )
                    )
                if (depth != SummaryDepth.BRIEF) {
                    body.put("generationConfig", JSONObject().put("maxOutputTokens", if (depth == SummaryDepth.DEEP) 4096 else 2048))
                }
                val bodyText = body.toString()
                fun call(model: String): Pair<Int, String> {
                    val req = Request.Builder()
                        .url("$API_BASE/models/$model:generateContent?key=$apiKey")
                        .post(bodyText.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                    return client.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
                }
                fun needsFallback(x: Pair<Int, String>) =
                    x.first == 429 || x.first == 503 || x.first == 404 || isOverloaded(x.first, x.second)
                var quotaMessage: String? = null
                val trace = mutableListOf<String>()
                var r: Pair<Int, String>? = null
                var retried = false // at most ONE 1500ms retry across the whole request
                fun attempt(model: String): Boolean {
                    var x = call(model)
                    if (x.first == 503 && !retried) {
                        retried = true
                        trace += "$model -> HTTP 503 (retry in 1500ms)"
                        Thread.sleep(1500)
                        x = call(model)
                    }
                    r = x
                    trace += "$model -> HTTP ${x.first}"
                    if (x.first == 429 && quotaMessage == null) {
                        quotaMessage = (parse(x.first, x.second) as? DigestResult.QuotaExceeded)?.googleMessage.orEmpty()
                    }
                    return !needsFallback(x)
                }
                // 429 / 503 / 404 immediately advance to the next model; no waiting.
                var done = CANDIDATES.any { attempt(it) }
                val last = r!!
                if (done) return@withContext parse(last.first, last.second)
                // Every model failed: report the full per-model trace so no earlier error is lost.
                val traceText = " [TRACE: ${trace.joinToString("; ")}]"
                when {
                    quotaMessage != null -> DigestResult.QuotaExceeded(quotaMessage!! + traceText)
                    isOverloaded(last.first, last.second) -> DigestResult.ServiceCongested(traceText)
                    else -> when (val fin = parse(last.first, last.second)) {
                        is DigestResult.NetworkOrApiError -> DigestResult.NetworkOrApiError(fin.detail + traceText)
                        else -> fin
                    }
                }            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IOException) {
                DigestResult.NetworkOrApiError(e.message ?: e.javaClass.simpleName)
            } catch (e: Exception) {
                DigestResult.NetworkOrApiError(e.message ?: e.javaClass.simpleName)
            }
        }

    /** Caps transcript input: BRIEF 15,000 chars, DEEP 45,000 (head + marker + tail, 2:1). */
    private fun condense(text: String, depth: SummaryDepth): String {
        val cap = depth.maxTranscriptChars
        if (text.length <= cap) return text
        val head = cap * 2 / 3
        return text.take(head) + "\n...[TRANSCRIPT CONDENSED]...\n" + text.takeLast(cap - head)
    }

    private fun isOverloaded(code: Int, raw: String): Boolean {
        if (code == 503) return true
        val err = runCatching { JSONObject(raw).optJSONObject("error") }.getOrNull() ?: return false
        val msg = err.optString("message")
        return err.optString("status") == "UNAVAILABLE" || msg.contains("high demand", true) ||
            msg.contains("overloaded", true)
    }

    private fun parse(code: Int, raw: String): DigestResult {
        val json = runCatching { JSONObject(raw) }.getOrNull()
        val err = json?.optJSONObject("error")
        val status = err?.optString("status").orEmpty()
        val message = err?.optString("message").orEmpty()
        val reason = err?.optJSONArray("details")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("reason") }.joinToString(",")
        }.orEmpty()

        if (code == 429 || status == "RESOURCE_EXHAUSTED") return DigestResult.QuotaExceeded(message)
        if (isOverloaded(code, raw)) return DigestResult.ServiceCongested()
        if (code == 400 || code == 403 || code == 401) {
            val keyError = reason.contains("API_KEY", true) || message.contains("API key", true) ||
                status == "PERMISSION_DENIED" || status == "UNAUTHENTICATED" || code == 403 || code == 401
            if (keyError) return DigestResult.InvalidKey
        }
        if (code !in 200..299) {
            val detail = message.ifBlank { "HTTP $code" }
            return DigestResult.NetworkOrApiError(detail)
        }
        if (json == null) return DigestResult.NetworkOrApiError("Malformed response")
        val text = runCatching {
            val parts = json.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            buildString { for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text")) }
        }.getOrDefault("").trim()
        if (text.isEmpty()) {
            val block = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            return DigestResult.NetworkOrApiError(if (block.isNotEmpty()) "Content blocked ($block)" else "Empty response")
        }
        return DigestResult.Success(text)
    }
}
