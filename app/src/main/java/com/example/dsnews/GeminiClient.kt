package com.example.dsnews

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * DS News Tool — Gemini API client (DS Recap Tool ရဲ့ GeminiClient.kt ကို အတိုချုပ် ပြန်ရေးထားတာ)။
 * Script + JSON plan ထုတ်ဖို့ပဲ သုံးတယ်။ Vosk မပါဘူး၊ Files API မပါဘူး။
 * Key မှားရင် / quota ကုန်ရင် IOException ပစ်တယ် — ခေါ်တဲ့နေရာက ဖမ်းပြီး မြန်မာလို ပြတယ်။
 */
object GeminiClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    // Recap နဲ့ အတူတူ — model တစ်ခု မရရင် နောက်တစ်ခုကို အလိုအလျောက် စမ်းတယ်
    private val models = listOf(
        "gemini-3-flash-preview",
        "gemini-flash-latest",
        "gemini-3.1-flash-lite"
    )

    /** prompt တစ်ခုကို စာသား အဖြေ ပြန်ပေးတယ်။ */
    suspend fun generateText(
        apiKey: String,
        prompt: String,
        log: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        var lastErr = ""
        for (m in models) {
            var attempt = 0
            while (attempt < 3) {
                attempt++
                log("Gemini ($m) ကို ခေါ်နေပါတယ်...")
                val (code, resp) = post(apiKey, m, prompt)
                when {
                    code == 200 -> return@withContext extractText(resp)
                    code == 404 -> {
                        lastErr = "$m model မရှိတော့ပါ"
                        log("⚠️ $lastErr၊ နောက် model စမ်းမယ်")
                        break
                    }
                    code == 429 || code == 503 -> {
                        lastErr = "HTTP $code (quota/လူများနေ)"
                        val d = 20_000L * attempt
                        log("⚠️ $lastErr၊ ${d / 1000} စက္ကန့်စောင့်မယ်")
                        delay(d)
                    }
                    code == 400 && resp.contains("API key not valid") ->
                        throw IOException("Gemini key မှားနေတယ် — aistudio.google.com က key အသစ် ယူပြီး ပြန်ထည့်ပါ။")
                    code == 400 && resp.contains("location is not supported") ->
                        throw IOException("Gemini က ဒီ ကွန်ရက်/VPN ရဲ့ နိုင်ငံကို ထောက်ပံ့မထားပါ။ VPN ကို US / Singapore / Japan လို ထောက်ပံ့တဲ့ နိုင်ငံကို ပြောင်းပြီး ပြန်စမ်းပါ။")
                    else -> throw IOException("Gemini HTTP $code: ${resp.take(300)}")
                }
            }
        }
        throw IOException("Gemini အလုပ်မလုပ်ပါ: $lastErr")
    }

    /** Gemini အဖြေထဲက ```json ... ``` fence ကို ဖြုတ်ပြီး JSON စာသား သက်သက် ထုတ်ပေးတယ်။ */
    fun extractJson(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```").trim()
            if (s.startsWith("json", ignoreCase = true)) s = s.substring(4).trim()
            if (s.endsWith("```")) s = s.removeSuffix("```").trim()
        }
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start >= 0 && end > start) s = s.substring(start, end + 1)
        return s
    }

    private fun post(apiKey: String, model: String, prompt: String): Pair<Int, String> {
        val body = JSONObject().apply {
            put("contents", org.json.JSONArray().put(JSONObject().apply {
                put("parts", org.json.JSONArray().put(JSONObject().put("text", prompt)))
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.7)
                put("maxOutputTokens", 4096)
            })
        }.toString()
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            return Pair(r.code, r.body?.string() ?: "")
        }
    }

    private fun extractText(resp: String): String {
        val obj = JSONObject(resp)
        val cands = obj.optJSONArray("candidates") ?: throw IOException("Gemini အဖြေ မမှန်ပါ: ${resp.take(200)}")
        if (cands.length() == 0) throw IOException("Gemini အဖြေ ဗလာ ဖြစ်နေပါတယ်")
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: throw IOException("Gemini အဖြေ မမှန်ပါ: ${resp.take(200)}")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        return sb.toString()
    }
}
