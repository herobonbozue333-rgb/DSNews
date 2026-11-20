package com.example.dsnews

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.jvm.Volatile

/**
 * Microsoft Edge read-aloud TTS (speech.platform.bing.com WebSocket) — မြန်မာအသံ Nilar/Thiha။
 * အရင် Bing Translator (www.bing.com/tfettts) က VPN IP ကို block လို့ (HTTP 500) Edge လမ်းကြောင်းကို
 * ပြောင်းထားတယ် (2026-11-12)။ အသံ အတူတူပဲ (my-MM-NilarNeural / my-MM-ThihaNeural)၊
 * endpoint + token (Sec-MS-GEC) ပဲ မတူတယ်။ API key မလိုဘူး၊ အလကားပါ။
 * (edge-tts ရဲ့ protocol ကို အတိအကျ လိုက်ထားတယ် — Sec-MS-GEC token ကို DRM သင်္ချာနဲ့ တွက်တယ်။)
 */
object BingTts {
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val SEC_MS_GEC_VERSION = "2-143.0.3650.75"
    private const val WSS_BASE =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // WebSocket အတွက် သီးသန့် client (read timeout မထား — audio လာတာ စောင့်တာ coroutine timeout နဲ့ ထိန်းတယ်)
    private val wsHttp = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private fun esc(s: String): String = s
        .filter { it == '\n' || it == '\t' || it >= ' ' }
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** ဒီ ကွန်ရက်ရဲ့ IP နဲ့ နိုင်ငံ (VPN ထွက်ပေါက် စစ်ဖို့) */
    suspend fun ipInfo(): String = withContext(Dispatchers.IO) {
        val errs = ArrayList<String>()
        for (url in listOf("https://ipinfo.io/json", "https://api.myip.com", "https://ipapi.co/json/")) {
            try {
                val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
                val txt = http.newCall(req).execute().use { r -> r.body?.string().orEmpty() }
                val j = org.json.JSONObject(txt)
                val ip = j.optString("ip")
                val country = j.optString("country").ifEmpty { j.optString("cc") }
                if (ip.isNotEmpty()) {
                    return@withContext "$ip · $country · ${j.optString("city")} · ${j.optString("org")}".trim()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errs.add("${url.substringAfter("//").substringBefore('/')}: ${e.javaClass.simpleName}")
            }
        }
        "စစ်လို့မရ (${errs.joinToString(", ")})"
    }

    // ------------------------------------------------------------ Edge DRM (Sec-MS-GEC)
    // edge-tts ရဲ့ DRM သင်္ချာကို အတိအကျ ပြန်ရေးထားတယ် (Node.js မှာ Python library နဲ့
    // token ၄ ခု တိုက်စစ်ပြီး — အားလုံး ကိုက်တယ်)။ UInt သုံးထားလို့ 32-bit overflow က
    // Python ရဲ့ & 0xFFFFFFFF နဲ့ အတူတူပဲ။
    private object EdgeDrm {
        private val SHA256_INITIAL = listOf(
            0x6A09E667u, 0xBB67AE85u, 0x3C6EF372u, 0xA54FF53Au,
            0x510E527Fu, 0x9B05688Cu, 0x1F83D9ABu, 0x5BE0CD19u
        )
        private val SHA256_STATE = listOf(
            0xFD9FBEA2u, 0x91243CE7u, 0xBE499094u, 0xCD2E1E0Au,
            0x6C006F49u, 0x04BD35ABu, 0xFEB0A7E4u, 0x03EFFBD2u
        )
        private val SHA256_SCHEDULE = listOf(
            0xEB24BF9Eu, 0x1558691Fu, 0xA0C09EE2u, 0x0CFA32BAu,
            0xA622EE8Cu, 0xD1DD9491u, 0xC0DD25A0u, 0xF9E70AB6u,
            0xF0C31573u, 0x7058CE4Eu, 0x7188E606u, 0x00E5AB33u,
            0xD258B060u, 0x72766564u
        )
        private val SHA256_ROUND_CONSTANTS = listOf(
            0x428A2F98u, 0x71374491u, 0xB5C0FBCFu, 0xE9B5DBA5u,
            0x3956C25Bu, 0x59F111F1u, 0x923F82A4u, 0xAB1C5ED5u,
            0xD807AA98u, 0x12835B01u, 0x243185BEu, 0x550C7DC3u,
            0x72BE5D74u, 0x80DEB1FEu, 0x9BDC06A7u, 0xC19BF174u,
            0xE49B69C1u, 0xEFBE4786u, 0x0FC19DC6u, 0x240CA1CCu,
            0x2DE92C6Fu, 0x4A7484AAu, 0x5CB0A9DCu, 0x76F988DAu,
            0x983E5152u, 0xA831C66Du, 0xB00327C8u, 0xBF597FC7u,
            0xC6E00BF3u, 0xD5A79147u, 0x06CA6351u, 0x14292967u,
            0x27B70A85u, 0x2E1B2138u, 0x4D2C6DFCu, 0x53380D13u,
            0x650A7354u, 0x766A0ABBu, 0x81C2C92Eu, 0x92722C85u,
            0xA2BFE8A1u, 0xA81A664Bu, 0xC24B8B70u, 0xC76C51A3u,
            0xD192E819u, 0xD6990624u, 0xF40E3585u, 0x106AA070u,
            0x19A4C116u, 0x1E376C08u, 0x2748774Cu, 0x34B0BCB5u,
            0x391C0CB3u, 0x4ED8AA4Au, 0x5B9CCA4Fu, 0x682E6FF3u,
            0x748F82EEu, 0x78A5636Fu, 0x84C87814u, 0x8CC70208u,
            0x90BEFFFAu, 0xA4506CEBu, 0xBEF9A3F7u, 0xC67178F2u
        )
        private const val SHA256_ADJUSTMENT = 0xC9DC06A7u
        private val SHA256_BLOCK_CONSTANTS =
            SHA256_ROUND_CONSTANTS.take(14) + SHA256_ADJUSTMENT + SHA256_ROUND_CONSTANTS.drop(15)
        private const val SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80u
        private const val SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0u
        private val CS64_COEFFICIENTS = listOf(
            0xE82E91B1u to 0xB32700D0u, 0x44DE24C6u to 0x88DFF51Du,
            0x3BEB65BCu to 0x29514038u, 0xC89C5933u to 0xB328CBDFu,
            0x8D1EC211u to 0xD405B4D5u, 0x314EF5D5u to 0x26E55F3Cu,
            0xE92491B1u to 0x607801C6u, 0xF0A5B9D5u to 0x82B2C1FBu,
            0x06064CF3u to 0xFA51A3DAu, 0xDA79FA60u to 0x207CC464u,
            0xBED20560u to 0x3C3C74F3u, 0x89F9C9F8u to 0xD2D14B61u,
            0x35FA38C6u to 0xBB04A2C1u, 0xCB49E3C6u to 0xC5626167u
        )
        private const val CS64_FACTOR = 0x82893933u
        private const val CS64_ADJUSTMENT0 = 0xF2F90B1Au
        private const val CS64_ADJUSTMENT1 = 0x0B1A0000u
        private const val WIN_EPOCH = 11644473600L

        private fun rotr(x: UInt, n: Int): UInt = (x shr n) or (x shl (32 - n))
        private fun rol16(v: UInt): UInt = (v shl 16) or (v shr 16)
        private fun sigma0(v: UInt): UInt =
            (v shr 7) xor (v shl 25) xor (v shr 18) xor (v shl 14) xor (v shr 3)
        private fun sigma1(v: UInt): UInt =
            (v shr 17) xor (v shl 15) xor (v shr 19) xor (v shl 13) xor (v shr 10)

        private fun wordsLe4Even(data: ByteArray): List<UInt> {
            val pad = (4 - (data.size % 4)) % 4
            val padded = data + ByteArray(pad)
            if (padded.isEmpty()) return emptyList()
            val words = ArrayList<UInt>()
            var i = 0
            while (i < padded.size) {
                words.add(
                    (padded[i].toUInt() and 0xFFu) or
                            ((padded[i + 1].toUInt() and 0xFFu) shl 8) or
                            ((padded[i + 2].toUInt() and 0xFFu) shl 16) or
                            ((padded[i + 3].toUInt() and 0xFFu) shl 24)
                )
                i += 4
            }
            if (words.size % 2 == 1) words.add(0u)
            return words
        }

        private fun expandSchedule(words: MutableList<UInt>, firstWord: Int = 16) {
            for (index in firstWord until 64) {
                val x = words[index - 2]
                val y = words[index - 15]
                val s1 = sigma1(x)
                val s0 = sigma0(y)
                words.add(s1 + words[index - 7] + s0 + words[index - 16])
            }
        }

        // SHA-256 compression — ပုံမှန် ၆၄ ခေါက် loop (edge-tts ရဲ့ ၈ ခေါက်ချင်း unroll နဲ့ သင်္ချာ အတူတူ)
        private fun sha256Rounds(
            state: List<UInt>, words: List<UInt>,
            firstRound: Int = 0, constants: List<UInt> = SHA256_ROUND_CONSTANTS
        ): List<UInt> {
            var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
            var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
            for (i in firstRound until 64) {
                val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = h + s1 + ch + constants[i] + words[i]
                val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            return listOf(a, b, c, d, e, f, g, h)
        }

        private fun sha256Init(data: ByteArray): List<UInt> {
            val words = MutableList(14) { 0u }
            words.add(
                ((data[0].toUInt() and 0xFFu) shl 16) or
                        ((data[1].toUInt() and 0xFFu) shl 8) or
                        (data[2].toUInt() and 0xFFu)
            )
            words.add(
                ((data[3].toUInt() and 0xFFu) shl 24) or
                        ((data[4].toUInt() and 0xFFu) shl 16) or
                        ((data[5].toUInt() and 0xFFu) shl 8) or
                        (data[6].toUInt() and 0xFFu)
            )
            for (index in 16 until 30) {
                var value = SHA256_SCHEDULE[index - 16]
                if (index - 2 >= 14) {
                    value += if (index == 16) (sigma1(words[index - 2]) xor SHA256_SIGMA1_ADJUSTMENT)
                    else sigma1(words[index - 2])
                }
                if (index - 7 >= 14) value += words[index - 7]
                if (index - 15 >= 14) {
                    value += if (index == 29) (sigma0(words[index - 15]) xor SHA256_SIGMA0_ADJUSTMENT)
                    else sigma0(words[index - 15])
                }
                words.add(value)
            }
            words.add(
                sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] +
                        SHA256_ADJUSTMENT - SHA256_ROUND_CONSTANTS[14]
            )
            expandSchedule(words, 31)
            val working = sha256Rounds(SHA256_STATE, words, 14, SHA256_BLOCK_CONSTANTS)
            return SHA256_INITIAL.mapIndexed { i, x -> x + working[i] }
        }

        private fun sha256Digest(data: ByteArray): ByteArray {
            require(data.size >= 7) { "Token salt must contain at least seven bytes" }
            val length = 57 + data.size
            val k = ((55 - length) % 64 + 64) % 64
            val bitLen = length.toLong() * 8L
            val lenBytes = ByteArray(8)
            for (i in 0 until 8) lenBytes[i] = ((bitLen ushr (56 - i * 8)) and 0xFF).toByte()
            val padded = data.copyOfRange(7, data.size) + byteArrayOf(0x80.toByte()) + ByteArray(k) + lenBytes
            var state = sha256Init(data.copyOfRange(0, 7))
            var off = 0
            while (off < padded.size) {
                val words = ArrayList<UInt>()
                for (i in 0 until 16) {
                    val o = off + i * 4
                    words.add(
                        ((padded[o].toUInt() and 0xFFu) shl 24) or
                                ((padded[o + 1].toUInt() and 0xFFu) shl 16) or
                                ((padded[o + 2].toUInt() and 0xFFu) shl 8) or
                                (padded[o + 3].toUInt() and 0xFFu)
                    )
                }
                expandSchedule(words)
                val working = sha256Rounds(state, words)
                state = state.mapIndexed { i, x -> x + working[i] }
                off += 64
            }
            val out = ByteArray(32)
            for (i in 0 until 8) {
                val w = state[i]
                out[i * 4] = ((w shr 24) and 0xFFu).toByte()
                out[i * 4 + 1] = ((w shr 16) and 0xFFu).toByte()
                out[i * 4 + 2] = ((w shr 8) and 0xFFu).toByte()
                out[i * 4 + 3] = (w and 0xFFu).toByte()
            }
            return out
        }

        private fun le32(b: ByteArray, o: Int): UInt =
            (b[o].toUInt() and 0xFFu) or
                    ((b[o + 1].toUInt() and 0xFFu) shl 8) or
                    ((b[o + 2].toUInt() and 0xFFu) shl 16) or
                    ((b[o + 3].toUInt() and 0xFFu) shl 24)

        private fun cs64Digest(data: ByteArray, digest: ByteArray): Pair<UInt, UInt> {
            val keyA = le32(digest, 0) or 1u
            val keyB = le32(digest, 4) or 1u
            val factors = listOf(keyA * CS64_FACTOR, keyB * CS64_FACTOR)
            val terms = ArrayList<UInt>()
            val offsets = ArrayList<UInt>()
            for ((index, pair) in CS64_COEFFICIENTS.withIndex()) {
                terms.add(pair.first * factors[index % 2])
                offsets.add(pair.second)
            }
            val wdata = wordsLe4Even(byteArrayOf(0) + data)
            for ((index, word) in wdata.withIndex()) {
                var term = word * (if (index % 2 == 0) keyA else keyB)
                var offset = rol16(word) * (if (index % 2 == 0) 0xEF0569FBu else 0xC31713DBu)
                if (index == 0) {
                    term += CS64_ADJUSTMENT0 * factors[0]
                    offset += CS64_ADJUSTMENT1
                }
                terms.add(term)
                offsets.add(offset)
            }
            var swapAcc = 0u
            var swapLast = 0u
            var state = 0u
            var acc = 0u
            var index = 0
            while (index < terms.size) {
                val x = terms[index]
                val y = terms[index + 1]
                var t = rol16(offsets[index] + x)
                var q = t * 0x76F50F34u
                swapAcc += rol16(q) * 0xC3EFEA97u + q
                t = rol16(offsets[index + 1] + y)
                q = t * 0x1881CE3Cu
                swapLast = rol16(q) * 0x35BD1EC9u + q
                swapAcc += swapLast
                state = state * keyA + x
                state = rol16(state) * 0xCF98B111u
                state = rol16(state) * 0x87085B9Fu
                state = rol16(state) * 0x12CEB96Du
                state = rol16(state) * 0x257E1D83u
                acc += state
                t = state * keyB + y
                t = rol16(t) * 0xA27416F5u
                t = rol16(t) * 0xD38396FFu
                state = rol16(rol16(t) * 0x7C932B89u) * 0xBFA49F69u
                acc += state
                index += 2
            }
            return (swapLast xor state) to (swapAcc xor acc)
        }

        private fun bytesToHex(b: ByteArray): String {
            val hex = "0123456789ABCDEF"
            val sb = StringBuilder(b.size * 2)
            for (x in b) {
                val v = x.toInt() and 0xFF
                sb.append(hex[v shr 4]).append(hex[v and 0x0F])
            }
            return sb.toString()
        }

        private fun tokenHash(data: ByteArray): Pair<String, String> {
            val digest = sha256Digest(data)
            val (w0, w1) = cs64Digest(data, digest)
            val csBytes = ByteArray(8)
            fun putLe(v: UInt, o: Int) {
                csBytes[o] = (v and 0xFFu).toByte()
                csBytes[o + 1] = ((v shr 8) and 0xFFu).toByte()
                csBytes[o + 2] = ((v shr 16) and 0xFFu).toByte()
                csBytes[o + 3] = ((v shr 24) and 0xFFu).toByte()
            }
            putLe(w0, 0)
            putLe(w1, 4)
            return bytesToHex(csBytes) to bytesToHex(digest)
        }

        fun generateSecMsGec(): String {
            val unixSec = System.currentTimeMillis() / 1000L
            var ticks = unixSec + WIN_EPOCH
            ticks -= ticks % 300L
            ticks *= 10_000_000L
            val data = "$ticks$TRUSTED_CLIENT_TOKEN".toByteArray(Charsets.US_ASCII)
            val (cs64, sha) = tokenHash(data)
            return "$cs64-$sha"
        }
    }

    // ------------------------------------------------------------ Edge WebSocket (persistent)
    // WebSocket တစ်ခုတည်း ဖွင့်ထားပြီး အသံထုတ်တိုင်း ပြန်သုံးတယ် — 433 ကြောင်း တစ်ကြောင်းချင်း
    // connection အသစ် ဖွင့်ရင် handshake အချိန် ကုန်လို့။ ပြိုင်တူ turn ၂ ခု (x2) ကို
    // X-RequestId နဲ့ ခွဲပြီး route လုပ်တယ်။ connection ပြတ်ရင် နောက် call က အသစ် ပြန်ချိတ်တယ်။
    private fun dateToString(): String {
        val fmt = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US
        )
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun configMessage(): String {
        return "X-Timestamp:${dateToString()}\r\n" +
                "Content-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n\r\n" +
                "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                "{\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"}," +
                "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
    }

    /** +5 / -5 ပုံစံ — အနှုတ် ဖြစ်ရင် '+' မထည့်ဘူး (မဟုတ်ရင် '+-5Hz' ဖြစ်သွားမယ်) */
    private fun signed(n: Int): String = if (n >= 0) "+$n" else "$n"

    private fun ssmlMessage(requestId: String, text: String, voice: String, ratePct: Int = 0, pitchHz: Int = 0): String {
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                "<voice name='$voice'>" +
                "<prosody pitch='${signed(pitchHz)}Hz' rate='${signed(ratePct)}%' volume='+0%'>${esc(text)}</prosody>" +
                "</voice></speak>"
        return "X-RequestId:$requestId\r\n" +
                "Content-Type:application/ssml+xml\r\n" +
                "X-Timestamp:${dateToString()}Z\r\n" +
                "Path:ssml\r\n\r\n" +
                ssml
    }

    private fun parsePath(text: String): String? {
        val head = text.substringBefore("\r\n\r\n", text)
        for (line in head.split("\r\n")) {
            if (line.startsWith("Path:")) return line.substringAfter("Path:").trim()
        }
        return null
    }

    private fun parseRequestId(headerText: String): String? {
        for (line in headerText.split("\r\n")) {
            if (line.startsWith("X-RequestId:")) {
                return line.substringAfter("X-RequestId:").trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun generateMuid(): String {
        val rnd = SecureRandom()
        val b = ByteArray(16)
        rnd.nextBytes(b)
        val hex = "0123456789ABCDEF"
        val sb = StringBuilder(32)
        for (x in b) {
            val v = x.toInt() and 0xFF
            sb.append(hex[v shr 4]).append(hex[v and 0x0F])
        }
        return sb.toString()
    }

    // တစ်ခု turn ရဲ့ အခြေအနေ (X-RequestId နဲ့ route လုပ်ဖို့)
    private class PendingTurn(val text: String) {
        val audio = ByteArrayOutputStream()
        val events = StringBuilder()
        var audioChunks = 0
        var lastResponse = ""
        val deferred = CompletableDeferred<ByteArray>()
        fun diag(): String {
            val head = text.take(60).replace("\n", " ").replace("\r", " ")
            return "ev=[$events] chunks=$audioChunks bytes=${audio.size()} len=${text.length} head=[$head] resp=[$lastResponse]"
        }
    }

    private val connMutex = Mutex()
    @Volatile private var sharedWs: WebSocket? = null
    @Volatile private var connReady: CompletableDeferred<WebSocket>? = null
    private val pending = ConcurrentHashMap<String, PendingTurn>()

    // စောင့်နေတဲ့ turn တွေကို အကုန် အပြီးသတ်တယ် (အသံရပြီးသားဆို အသံပေး၊ မရသေးရင် error)
    private fun failPendingTurns(ex: IOException) {
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val turn = it.next().value
            it.remove()
            val bytes = turn.audio.toByteArray()
            if (bytes.isNotEmpty()) turn.deferred.complete(bytes)
            else turn.deferred.completeExceptionally(ex)
        }
    }

    private fun dropConnection() {
        val ws = sharedWs
        sharedWs = null
        connReady = null
        failPendingTurns(IOException("Edge TTS: ချိတ်ဆက်မှု ပြတ်သွားတယ်"))
        try { ws?.close(1000, null) } catch (_: Exception) { }
    }

    private val sharedListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket != sharedWs) return
            try { webSocket.send(configMessage()) } catch (_: Exception) { }
            connReady?.let { ready ->
                if (!ready.isCompleted) ready.complete(webSocket)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val head = text.substringBefore("\r\n\r\n", text)
            val path = parsePath(text)
            val reqId = parseRequestId(head) ?: return
            val turn = pending[reqId] ?: return
            turn.events.append("txt:$path;")
            if (path == "response") {
                turn.lastResponse = text.substringAfter("\r\n\r\n", "").take(200)
            }
            if (path == "turn.end") {
                pending.remove(reqId)
                val bytes = turn.audio.toByteArray()
                if (bytes.isEmpty()) {
                    turn.deferred.completeExceptionally(IOException("Edge TTS: အသံ မရပါ | ${turn.diag()}"))
                } else {
                    turn.deferred.complete(bytes)
                }
            }
            // turn.start / audio.metadata -> လျစ်လျူရှုတယ်
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                val arr = bytes.toByteArray()
                if (arr.size < 2) return
                val headerLen = ((arr[0].toInt() and 0xFF) shl 8) or (arr[1].toInt() and 0xFF)
                if (headerLen + 2 > arr.size) return
                // BUG FIX: binary message ရဲ့ ပထမ 2 byte က header length prefix —
                // header စာသားက byte 2 ကနေ စတယ်။ offset 0 ကနေ ဖတ်ရင် ပထမ line
                // (X-RequestId) ပျက်ပြီး parseRequestId က null ပြန်၊ audio chunk
                // တိုင်းကို ပစ်ပစ်လို့ chunks=0 ဖြစ်နေတာ။ (v1/v2/v3 မှာ RequestId နဲ့
                // route မလုပ်လို့ ဒီ bug က မပေါ်ဘူး — persistent WS v4 မှ ပေါ်တာ။)
                val headerText = String(arr, 2, headerLen, Charsets.UTF_8)
                if (!headerText.contains("Path:audio")) return
                val reqId = parseRequestId(headerText) ?: return
                val turn = pending[reqId] ?: return
                val start = headerLen + 2
                if (start < arr.size) {
                    turn.audio.write(arr, start, arr.size - start)
                    turn.audioChunks++
                }
                // start == size ဆိုရင် အသံမပါတဲ့ အပိတ် chunk — လျစ်လျူရှုတယ်
            } catch (_: Exception) {
                // chunk ပျက်ရင် ကျော်တယ်
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket != sharedWs) return
            val ex = IOException("Edge TTS: ${t.message}")
            connReady?.let { ready ->
                if (!ready.isCompleted) ready.completeExceptionally(ex)
            }
            dropConnection()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket != sharedWs) return
            dropConnection()
        }
    }

    private suspend fun ensureConnection(): WebSocket {
        // Fast path: connection ရှိပြီးသား ဆို ready ဖြစ်တာကို စောင့်တယ်
        // (ပြီးသားဆို တန်း return တယ်၊ exception နဲ့ ပြီးသွားရင် အသစ် ပြန်ချိတ်တယ်)
        connReady?.let { ready ->
            try {
                val ws = ready.await()
                if (sharedWs == ws) return ws
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // connection ဖွင့်မရခဲ့ဘူး — အောက်မှာ အသစ် ပြန်ချိတ်တယ်
            }
        }
        return connMutex.withLock {
            connReady?.let { ready ->
                try {
                    val ws = ready.await()
                    if (sharedWs == ws) return@withLock ws
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // connection ဖွင့်မရခဲ့ဘူး — အောက်မှာ အသစ် ပြန်ချိတ်တယ်
                }
            }
            val connectionId = UUID.randomUUID().toString().replace("-", "")
            val gec = EdgeDrm.generateSecMsGec()
            val muid = generateMuid()
            val url = "$WSS_BASE?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
                    "&ConnectionId=$connectionId" +
                    "&Sec-MS-GEC=$gec" +
                    "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"
            val request = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept-Encoding", "gzip, deflate, br, zstd")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("Cookie", "muid=$muid;")
                .build()
            val ready = CompletableDeferred<WebSocket>()
            connReady = ready
            val ws = wsHttp.newWebSocket(request, sharedListener)
            sharedWs = ws
            val opened = withTimeoutOrNull(20_000) { ready.await() }
            if (opened == null) {
                if (sharedWs == ws) sharedWs = null
                if (connReady == ready) connReady = null
                try { ws.close(1000, null) } catch (_: Exception) { }
                throw IOException("Edge TTS: ချိတ်ဆက်မှု မရပါ (timeout)")
            }
            opened
        }
    }

    private suspend fun synthesizeOnce(text: String, voice: String, ratePct: Int = 0, pitchHz: Int = 0): ByteArray {
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val turn = PendingTurn(text)
        pending[requestId] = turn
        try {
            val ws = ensureConnection()
            val sent = ws.send(ssmlMessage(requestId, text, voice, ratePct, pitchHz))
            if (!sent) {
                dropConnection()
                throw IOException("Edge TTS: မက်ဆေ့ချ် ပို့လို့မရပါ | ${turn.diag()}")
            }
            turn.events.append("sent;")
            val result = withTimeoutOrNull(90_000) { turn.deferred.await() }
            if (result != null) return result
            // 90s ကျော်သွားတယ် — connection ပျက်နေနိုင်လို့ ဖြုတ်ပြီး retry က အသစ် ပြန်ချိတ်မယ်
            pending.remove(requestId)
            dropConnection()
            throw IOException("Edge TTS: အချိန်ကုန်သွားတယ် (90s) | ${turn.diag()}")
        } catch (e: Exception) {
            pending.remove(requestId)
            throw e
        }
    }

    /** MP3 bytes ပြန်ပေးတယ် — Edge TTS ပဲ သုံးတယ် (Bing Translator ဖြုတ်ထားတယ်) */
    suspend fun synthesize(text: String, voice: String, ratePct: Int = 0, pitchHz: Int = 0): ByteArray = withContext(Dispatchers.IO) {
        // script tag ([N] / [D ...]) ပါလာရင် ဖြုတ်တယ် — Edge server က tag ပါတဲ့
        // စာသားကို အသံ မထုတ်ပေးဘဲ turn.end ပဲ ပြန်ပို့လို့။ (နောက်ဆုံး ကာကွယ်မှု)
        val cleanText = text.replace(Regex("\\[N\\]"), "")
            .replace(Regex("\\[D[^\\]]*\\]"), "")
            .trim()
        if (cleanText.isEmpty()) throw IOException("Edge TTS: tag ပဲ ကျန်တဲ့ စာသား (အသံထုတ်စရာ မရှိ)")
        var last = ""
        for (attempt in 0..2) {
            try {
                val result = withTimeoutOrNull(90_000) { synthesizeOnce(cleanText, voice, ratePct, pitchHz) }
                if (result != null) return@withContext result
                last = "Timeout (90s)"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = "${e.javaClass.simpleName}: ${e.message}"
            }
            if (attempt < 2) Thread.sleep(1200)
        }
        throw IOException("Edge TTS မရပါ: $last (VPN ပိတ်ပြီး / ခဏနေပြီး ပြန်စမ်းကြည့်ပါ)")
    }
}
