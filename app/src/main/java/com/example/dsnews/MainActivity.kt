package com.example.dsnews

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * DS News Tool V1 — Zippy News လို workflow + Hybrid ပုံစံ။
 * App ထဲမှာ Gemini တိုက်ရိုက်သုံးတယ် (Gemini app ပြောင်း၊ JSON ကူးစရာ မလို)။
 * အသံ Edge TTS (Thiha/Nilar)၊ 9:16 video၊ Template ၃ ခု၊ Ticker + Watermark။
 * YouTube ကို App က ရှာပေးတယ်၊ footage file ကို ဖုန်းထဲက ရွေးထည့်တယ် —
 * App ကိုယ်တိုင် YouTube ကနေ download မလုပ်ဘူး (လုံခြုံတဲ့နည်း)။
 *
 * အဆင့်: သတင်းထည့် → Script ထုတ် (Gemini) → Script ပြင် → Video လုပ် (TTS + FFmpeg) → Save/Share
 */
class MainActivity : AppCompatActivity() {

    // Neon Gaming — DS Recap Tool / Myanmar TTS နဲ့ အတူတူ
    private val colBg = "#05070F"
    private val colCard = "#0E1526"
    private val colCyan = "#00E5FF"
    private val colMagenta = "#FF2D95"
    private val colText = "#EAF6FF"
    private val colMuted = "#8A9BB8"

    private val voices = listOf(
        "သီဟ · အမျိုးသား" to "my-MM-ThihaNeural",
        "နီလာ · အမျိုးသမီး" to "my-MM-NilarNeural"
    )

    private data class PlanLine(val text: String, val photo: Int)

    private lateinit var etGeminiKey: EditText
    private lateinit var etYtKey: EditText
    private lateinit var etNews: EditText
    private lateinit var etSource: EditText
    private lateinit var spTemplate: Spinner
    private lateinit var spVoice: Spinner
    private lateinit var spDuration: Spinner
    private lateinit var sbSpeed: SeekBar
    private lateinit var tvSpeed: TextView
    private lateinit var tvMediaInfo: TextView
    private lateinit var etYtQuery: EditText
    private lateinit var ytResults: LinearLayout
    private lateinit var etScript: EditText
    private lateinit var tvPlanInfo: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnScript: Button
    private lateinit var btnRender: Button
    private lateinit var btnStop: Button
    private lateinit var btnSave: Button
    private lateinit var btnShare: Button
    private lateinit var videoView: VideoView

    private var photoUris: List<Uri> = emptyList()
    private var videoUri: Uri? = null
    private var planTitle = ""
    private var planTicker = ""
    private var planLines: List<PlanLine> = emptyList()
    private var lastOutput: File? = null
    private var job: Job? = null
    private var busy = false

    private val prefs by lazy { getSharedPreferences("dsnews", Context.MODE_PRIVATE) }
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val pickImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            photoUris = uris.take(3)
            videoUri = null
            tvMediaInfo.text = "ပုံ ${photoUris.size} ပုံ ရွေးထားတယ် (video မပါဘူး)"
        }
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            videoUri = uri
            photoUris = emptyList()
            tvMediaInfo.text = "Video file ရွေးထားတယ် (ပုံ မပါဘူး)"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        etGeminiKey.setText(prefs.getString("gemini_key", ""))
        etYtKey.setText(prefs.getString("yt_key", ""))
        updateSpeedLabel()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    // ---------- UI ----------

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun color(hex: String): Int = Color.parseColor(hex)

    private fun makeButton(text: String, bg: String): Button = Button(this).apply {
        this.text = text
        setBackgroundColor(color(bg))
        setTextColor(Color.WHITE)
        textSize = 16f
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        }
    }

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(colCyan))
        textSize = 17f
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(colMuted))
        textSize = 13f
        setPadding(0, 0, 0, dp(4))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(color(colCard))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        }
    }

    private fun input(hintText: String, lines: Int = 1, hidden: Boolean = false): EditText = EditText(this).apply {
        hint = hintText
        setTextColor(color(colText))
        setHintTextColor(color(colMuted))
        setBackgroundColor(Color.parseColor("#16203A"))
        setPadding(dp(10), dp(10), dp(10), dp(10))
        minLines = lines
        if (lines > 1) setLines(lines)
        inputType = if (hidden) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        }
    }

    private fun spinner(items: List<String>): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(colBg))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val scroll = ScrollView(this).apply { addView(root) }

        root.addView(TextView(this).apply {
            text = "📰 DS News Tool"
            setTextColor(color(colCyan))
            textSize = 24f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "သတင်း → မြန်မာ script → အသံ + 9:16 video"
            setTextColor(color(colMuted))
            textSize = 13f
            gravity = Gravity.CENTER
        })

        // Key တွေ
        root.addView(title("🔑 Key တွေ (တစ်ခါပဲ ထည့်ရမယ်)"))
        val keyCard = card()
        etGeminiKey = input("Gemini Key — aistudio.google.com က ယူပါ", hidden = true)
        keyCard.addView(TextView(this).apply { text = "Gemini Key *"; setTextColor(color(colText)) })
        keyCard.addView(etGeminiKey)
        etYtKey = input("YouTube API Key (မရှိရင် ဗလာ ထားလို့ရတယ်)", hidden = true)
        keyCard.addView(TextView(this).apply { text = "YouTube API Key (ရှာချင်မှ လိုတယ်)"; setTextColor(color(colText)) })
        keyCard.addView(etYtKey)
        root.addView(keyCard)

        // သတင်း input
        root.addView(title("📰 သတင်း ထည့်ပါ"))
        val newsCard = card()
        etNews = input("မူရင်း သတင်းစာသား ကူးထည့်ပါ — ခေါင်းစဉ်ပဲ ပေးလို့ မရဘူး (fake news ဖြစ်မှာစိုးလို့)", lines = 6)
        newsCard.addView(etNews)
        etSource = input("ရင်းမြစ် နာမည် — ဥပမာ Sky Sports")
        newsCard.addView(etSource)
        root.addView(newsCard)
        root.addView(hint("Link ပဲ ထည့်ရင် စာသား ဆွဲမရနိုင်ဘူး — စာသား ကူးထည့်တာက အသေချာဆုံးပဲ။"))

        // ရွေးချယ်မှုတွေ
        root.addView(title("⚙️ ရွေးချယ်မှု"))
        val optCard = card()
        optCard.addView(TextView(this).apply { text = "Template"; setTextColor(color(colText)) })
        spTemplate = spinner(listOf("Classic", "Breaking", "Sport"))
        optCard.addView(spTemplate)
        optCard.addView(TextView(this).apply { text = "အသံ"; setTextColor(color(colText)) })
        spVoice = spinner(voices.map { it.first })
        optCard.addView(spVoice)
        optCard.addView(TextView(this).apply { text = "အချိန်"; setTextColor(color(colText)) })
        spDuration = spinner(listOf("30 စက္ကန့်", "60 စက္ကန့်", "90 စက္ကန့်"))
        spDuration.setSelection(1)
        optCard.addView(spDuration)
        tvSpeed = TextView(this).apply { setTextColor(color(colText)) }
        optCard.addView(tvSpeed)
        sbSpeed = SeekBar(this).apply {
            max = 70 // 80% .. 150%
            progress = 40 // 1.2x — Zippy အတိုင်း
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = updateSpeedLabel()
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        optCard.addView(sbSpeed)
        root.addView(optCard)

        // ပုံ / video
        root.addView(title("🖼️ ပုံ / Video (မထည့်လဲ ရတယ်)"))
        val mediaCard = card()
        tvMediaInfo = TextView(this).apply { text = "မရွေးရသေးဘူး — အရောင် နောက်ခံနဲ့ လုပ်မယ်"; setTextColor(color(colMuted)) }
        mediaCard.addView(tvMediaInfo)
        val btnPhotos = makeButton("📷 ပုံ ၁–၃ ပုံ ရွေးမယ်", colCyan)
        btnPhotos.setOnClickListener { pickImages.launch("image/*") }
        mediaCard.addView(btnPhotos)
        val btnVideo = makeButton("🎬 ဖုန်းထဲက video ရွေးမယ်", colCyan)
        btnVideo.setOnClickListener { pickVideo.launch("video/*") }
        mediaCard.addView(btnVideo)
        root.addView(mediaCard)

        // YouTube ရှာတာ
        root.addView(title("🔍 YouTube မှာ ရှာမယ် (စာရင်းပဲ ပြတယ်)"))
        val ytCard = card()
        etYtQuery = input("ဥပမာ — Man Utd 4-4 Aston Villa")
        ytCard.addView(etYtQuery)
        val btnYt = makeButton("YouTube ရှာမယ်", colMagenta)
        btnYt.setOnClickListener { searchYouTube() }
        ytCard.addView(btnYt)
        ytResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ytCard.addView(ytResults)
        root.addView(ytCard)
        root.addView(hint("App က download မလုပ်ဘူး — ရှာတွေ့တဲ့ video နဲ့ ကိုက်တဲ့ file ကို ဖုန်းထဲက ရွေးထည့်ပါ။"))

        // Script
        root.addView(title("📝 Script"))
        btnScript = makeButton("✨ Script ထုတ်မယ် (Gemini)", colMagenta)
        btnScript.setOnClickListener { makeScript() }
        root.addView(btnScript)
        tvPlanInfo = TextView(this).apply { setTextColor(color(colMuted)); textSize = 13f }
        root.addView(tvPlanInfo)
        etScript = input("Script ဒီမှာ ပေါ်လာမယ် — ဖတ်ပြီး ပြင်လို့ရတယ် (တစ်ကြောင်းချင်း = အသံ တစ်ပိုင်း)", lines = 8)
        root.addView(etScript)

        // Render
        root.addView(title("🎬 Video လုပ်မယ်"))
        btnRender = makeButton("▶ Video လုပ်မယ်", colCyan)
        btnRender.setOnClickListener { renderVideo() }
        root.addView(btnRender)
        btnStop = makeButton("⏹ ရပ်မယ်", "#334155")
        btnStop.setOnClickListener { job?.cancel(); setBusy(false, "ရပ်လိုက်ပြီ") }
        root.addView(btnStop)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(progressBar)
        tvStatus = TextView(this).apply { setTextColor(color(colText)); textSize = 14f; setPadding(0, dp(8), 0, 0) }
        root.addView(tvStatus)

        videoView = VideoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(270), dp(480)).apply {
                topMargin = dp(10)
                gravity = Gravity.CENTER
            }
            visibility = View.GONE
        }
        val mediaController = MediaController(this).apply { setAnchorView(videoView) }
        videoView.setMediaController(mediaController)
        root.addView(videoView)

        btnSave = makeButton("💾 Gallery မှာ သိမ်းမယ် (Movies/DS News)", colCyan)
        btnSave.setOnClickListener { saveOutput() }
        btnSave.visibility = View.GONE
        root.addView(btnSave)
        btnShare = makeButton("📤 Share လုပ်မယ်", colMagenta)
        btnShare.setOnClickListener { shareOutput() }
        btnShare.visibility = View.GONE
        root.addView(btnShare)

        return scroll
    }

    private fun updateSpeedLabel() {
        val speed = (80 + sbSpeed.progress) / 100.0
        tvSpeed.text = "အမြန်/နှေး: ${"%.1f".format(speed)}x"
    }

    private fun speedRatePct(): Int = sbSpeed.progress - 20 // 1.2x → +20%

    private fun durationSec(): Int = when (spDuration.selectedItemPosition) {
        0 -> 30
        2 -> 90
        else -> 60
    }

    private fun setBusy(b: Boolean, status: String = "") {
        busy = b
        btnScript.isEnabled = !b
        btnRender.isEnabled = !b
        progressBar.visibility = if (b) View.VISIBLE else View.GONE
        if (status.isNotEmpty()) tvStatus.text = status
    }

    private fun showError(title: String, msg: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("အိုကေ", null)
                .show()
        }
    }

    private fun saveKeys() {
        prefs.edit()
            .putString("gemini_key", etGeminiKey.text.toString().trim())
            .putString("yt_key", etYtKey.text.toString().trim())
            .apply()
    }

    // ---------- YouTube ရှာတာ ----------

    private fun searchYouTube() {
        val key = etYtKey.text.toString().trim()
        val q = etYtQuery.text.toString().trim()
        if (key.isEmpty()) {
            showError("YouTube Key လိုတယ်", "YouTube Data API v3 key ထည့်မှ ရှာလို့ရမယ်။\nconsole.cloud.google.com → Project → YouTube Data API v3 Enable → Credentials → API key")
            return
        }
        if (q.isEmpty()) {
            Toast.makeText(this, "ရှာချင်တာ ရိုက်ထည့်ပါ", Toast.LENGTH_SHORT).show()
            return
        }
        if (busy) return
        setBusy(true, "YouTube ရှာနေတယ်...")
        job = lifecycleScope.launch {
            try {
                val url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=8" +
                    "&q=${Uri.encode(q)}&key=$key"
                val resp = withContext(Dispatchers.IO) {
                    http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() ?: "" }
                }
                val items = JSONObject(resp).optJSONArray("items")
                ytResults.removeAllViews()
                if (items == null || items.length() == 0) {
                    tvStatus.text = "ရှာမတွေ့ဘူး — စကားလုံး ပြောင်းပြီး ပြန်ရှာပါ"
                } else {
                    for (i in 0 until items.length()) {
                        val it = items.getJSONObject(i)
                        val sn = it.getJSONObject("snippet")
                        val title = sn.optString("title")
                        val channel = sn.optString("channelTitle")
                        val vid = it.optJSONObject("id")?.optString("videoId") ?: ""
                        val b = Button(this@MainActivity).apply {
                            text = "▶ $title\n$channel"
                            textSize = 13f
                            setTextColor(color(colText))
                            setBackgroundColor(Color.parseColor("#16203A"))
                            setOnClickListener {
                                etSource.setText(channel)
                                tvStatus.text = "ရွေးထားတယ်: $title\nဒီ video နဲ့ ကိုက်တဲ့ file ကို ဖုန်းထဲက ရွေးပါ — App က download မလုပ်ဘူး။"
                                Toast.makeText(this@MainActivity, "ရင်းမြစ်: $channel", Toast.LENGTH_SHORT).show()
                            }
                        }
                        ytResults.addView(b)
                    }
                    tvStatus.text = "တွေ့ပြီ ${items.length()} ခု — တစ်ခုနှိပ်ရင် ရင်းမြစ် ဖြည့်ပေးမယ်"
                }
            } catch (e: Exception) {
                showError("YouTube ရှာမရပါ", "${e.message}\n\nKey မှန်လား၊ internet ရှိလား စစ်ပါ။")
            } finally {
                setBusy(false)
            }
        }
    }

    // ---------- Script (Gemini) ----------

    private fun buildPrompt(news: String, dur: Int): String {
        val lineCount = when (dur) {
            30 -> "၂-၃ ကြောင်း"
            90 -> "၆-၈ ကြောင်း"
            else -> "၄-၅ ကြောင်း"
        }
        return """
အောက်က မူရင်း သတင်းစာသားကို ဖတ်ပြီး မြန်မာ သတင်း video အတွက် script + plan ကို JSON တစ်ခုတည်း ပြန်ပေးပါ။
စည်းကမ်း:
- မူရင်းစာမှာ ပါတဲ့ အချက်ပဲ သုံးပါ။ မပါတဲ့ အချက် အသစ် လုံးဝ မထည့်ပါနဲ့ (fake news မဖြစ်အောင်)။
- မြန်မာလို ရေးပါ။ Hook ၁ ကြောင်းနဲ့ စပါ၊ ပြီးရင် အဓိက အချက် ၃-၄ ချက်၊ အဆုံး ၁ ကြောင်း။
- စုစုပေါင်း $lineCount ဖြစ်ရမယ် (ဗီဒီယို $dur စက္ကန့်စာ)။ တစ်ကြောင်းချင်း စာကြောင်း ၁-၂ ကြောင်းပဲ ဖြစ်ရမယ်။
- JSON သက်သက် ပြန်ပေးပါ — markdown fence မပါစေနဲ့။
- ပုံစံ:
{"title": "ခေါင်းစဉ် တို", "ticker": "အောက်မှာ ပြေးမယ့် စာတို", "lines": [{"text": "အသံဖတ်မယ့် စာသား"}, {"text": "နောက် စာသား"}]}

မူရင်း သတင်းစာသား:
$news
""".trimIndent()
    }

    private fun makeScript() {
        val key = etGeminiKey.text.toString().trim()
        val news = etNews.text.toString().trim()
        if (key.isEmpty()) {
            showError("Gemini Key လိုတယ်", "aistudio.google.com → Get API key → Create API key → key ကို ဒီမှာ ထည့်ပါ")
            return
        }
        if (news.length < 20) {
            showError("သတင်း နည်းနေတယ်", "မူရင်း သတင်းစာသား ကူးထည့်ပါ — ခေါင်းစဉ်ပဲ ပေးလို့ မရဘူး")
            return
        }
        if (busy) return
        saveKeys()
        setBusy(true, "Gemini script ရေးနေတယ်...")
        job = lifecycleScope.launch {
            try {
                val raw = GeminiClient.generateText(key, buildPrompt(news, durationSec())) { log ->
                    runOnUiThread { tvStatus.text = log } // generateText က IO thread မှာ ခေါ်တယ်
                }
                val plan = JSONObject(GeminiClient.extractJson(raw))
                planTitle = plan.optString("title")
                planTicker = plan.optString("ticker")
                val arr = plan.optJSONArray("lines") ?: throw IOException("Gemini အဖြေမှာ lines မပါဘူး")
                val lines = mutableListOf<PlanLine>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val t = o.optString("text").trim()
                    if (t.isNotEmpty()) lines.add(PlanLine(t, o.optInt("photo", -1)))
                }
                if (lines.isEmpty()) throw IOException("Gemini script ဗလာ ဖြစ်နေပါတယ်")
                planLines = lines
                etScript.setText(lines.joinToString("\n") { it.text })
                tvPlanInfo.text = "ခေါင်းစဉ်: $planTitle\nTicker: $planTicker\nအသံ ${lines.size} ပိုင်း — ပြင်ပြီးမှ \"Video လုပ်မယ်\" နှိပ်ပါ"
                tvStatus.text = "Script ရပြီ — ဖတ်ပြီး ပြင်လို့ရတယ်"
            } catch (e: Exception) {
                showError("Script ထုတ်မရပါ", e.message ?: "အကြောင်းမသိ error")
                tvStatus.text = "Script မရသေးဘူး"
            } finally {
                setBusy(false)
            }
        }
    }

    // ---------- Render (TTS + FFmpeg) ----------

    private fun renderVideo() {
        val key = etGeminiKey.text.toString().trim()
        if (key.isEmpty()) {
            showError("Gemini Key လိုတယ်", "Key ထည့်မှ Script ထုတ်လို့ရမယ်")
            return
        }
        val edited = etScript.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (edited.isEmpty()) {
            showError("Script မရှိသေးဘူး", "အရင် \"Script ထုတ်မယ်\" နှိပ်ပါ")
            return
        }
        if (busy) return
        saveKeys()
        setBusy(true, "အသံ ဖတ်နေတယ်...")
        job = lifecycleScope.launch {
            try {
                val workDir = File(cacheDir, "dsnews_${System.currentTimeMillis()}").apply { mkdirs() }
                val voice = voices[spVoice.selectedItemPosition].second
                val rate = speedRatePct()
                val renderLines = mutableListOf<NewsRenderer.Line>()
                var cursor = 0.0
                edited.forEachIndexed { i, text ->
                    tvStatus.text = "အသံ တည်ဆောက်နေတယ် ${i + 1}/${edited.size}"
                    val mp3 = BingTts.synthesize(text, voice, rate, 0)
                    val f = File(workDir, "line_$i.mp3")
                    FileOutputStream(f).use { it.write(mp3) }
                    val dur = audioDurationSec(f)
                    val photo = if (i < planLines.size) planLines[i].photo else -1
                    renderLines.add(NewsRenderer.Line(text, f, cursor, cursor + dur, photo))
                    cursor += dur + 0.15
                }
                val total = (renderLines.lastOrNull()?.endSec ?: 0.0).coerceAtLeast(1.0)

                // ပုံ / video ကို cache file အဖြစ် ကူးတယ် (FFmpeg က local file နဲ့ လုပ်မှ စိတ်ချရတယ်)
                // + FFmpeg render က blocking ဖြစ်လို့ IO thread မှာ လုပ်တယ် (UI freeze မဖြစ်အောင်)
                val (photoFiles, videoFile) = withContext(Dispatchers.IO) {
                    val pf = photoUris.mapIndexed { i, uri ->
                        copyUriToFile(uri, File(workDir, "photo_$i.jpg"))
                    }
                    val vf = videoUri?.let { copyUriToFile(it, File(workDir, "user_video.mp4")) }
                    Pair(pf, vf)
                }

                tvStatus.text = "Video ပေါင်းနေတယ်..."
                val template = NewsRenderer.TEMPLATES[spTemplate.selectedItemPosition]
                val out = withContext(Dispatchers.IO) {
                    NewsRenderer.render(
                        context = this@MainActivity,
                        workDir = workDir,
                        lines = renderLines,
                        totalDurSec = total,
                        photos = photoFiles,
                        userVideo = videoFile,
                        template = template,
                        title = planTitle.ifBlank { "သတင်း" },
                        ticker = planTicker.ifBlank { planTitle },
                        source = etSource.text.toString().trim(),
                        onProgress = { msg -> runOnUiThread { tvStatus.text = msg } }
                    )
                }
                lastOutput = out
                videoView.visibility = View.VISIBLE
                videoView.setVideoURI(Uri.fromFile(out))
                videoView.start()
                btnSave.visibility = View.VISIBLE
                btnShare.visibility = View.VISIBLE
                tvStatus.text = "ပြီးပြီ — ${"%.0f".format(total)} စက္ကန့် video (9:16)။ Preview ကြည့်ပြီး Save/Share လုပ်ပါ။"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    tvStatus.text = "ရပ်လိုက်ပြီ"
                } else {
                    showError("Video လုပ်မရပါ", e.message ?: "အကြောင်းမသိ error")
                    tvStatus.text = "Video မရသေးဘူး — error ကို screenshot ပို့ပေးပါ"
                }
            } finally {
                setBusy(false)
            }
        }
    }

    private fun audioDurationSec(f: File): Double {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
        } catch (e: Exception) {
            0.0
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    private fun copyUriToFile(uri: Uri, out: File): File {
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        } ?: throw IOException("File ဖတ်မရပါ: $uri")
        return out
    }

    // ---------- Save / Share ----------

    private fun saveOutput() {
        val out = lastOutput ?: run {
            Toast.makeText(this, "Video မရှိသေးဘူး", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val name = "DS_news_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DS News")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IOException("Gallery မှာ သိမ်းမရပါ")
                    contentResolver.openOutputStream(uri)?.use { o -> out.inputStream().use { it.copyTo(o) } }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "DS News").apply { mkdirs() }
                    out.copyTo(File(dir, name), overwrite = true)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "သိမ်းပြီးပြီ — Movies/DS News/$name", Toast.LENGTH_LONG).show()
                    tvStatus.text = "သိမ်းပြီးပြီ — Movies/DS News/$name"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showError("သိမ်းမရပါ", e.message ?: "") }
            }
        }
    }

    private fun shareOutput() {
        val out = lastOutput ?: return
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", out)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share လုပ်မယ်"))
    }
}
