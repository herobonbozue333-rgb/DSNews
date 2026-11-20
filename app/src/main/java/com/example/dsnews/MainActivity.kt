package com.example.dsnews

import android.content.ClipData
import android.content.ClipboardManager
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
 * DS News Tool V2 — Zippy News လို workflow အတိုင်း (အပြင် Gemini + JSON paste)။
 *
 * Key ထည့်စရာ လုံးဝ မလိုတော့ဘူး — Gemini က အပြင် Gemini app က Google account နဲ့ လုပ်တယ်၊
 * YouTube search လည်း ဒီ workflow မှာ မပါဘူး (ဖြုတ်ထားတယ်)။
 *
 * အဆင့်: သတင်းထည့် → Gemini အပြင်ထုတ်မယ် (prompt + video Share) →
 * Gemini က JSON plan ပြန်ပေးတယ် → Plan JSON ကူးထည့် → Plan ဖတ်မယ် →
 * Script ပြင် → Video လုပ် (TTS + FFmpeg) → Save/Share
 *
 * JSON plan က ကိုယ်ပိုင် schema — title / ticker / template / voice / music_mood /
 * shots [{text, move, image, credit}] / sources။ Zippy ရဲ့ prompt စာသား၊
 * template ဒီဇိုင်း၊ code တွေ အတိအကျ ကူးထားတာ မဟုတ်ဘူး — ကိုယ်ပိုင် code နဲ့ ပြန်ဆောက်ထားတာ။
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
    private data class Shot(val text: String, val imageUrl: String, val credit: String, val move: String)

    private lateinit var etNews: EditText
    private lateinit var etSource: EditText
    private lateinit var etLink: EditText
    private lateinit var spTemplate: Spinner
    private lateinit var spVoice: Spinner
    private lateinit var spDuration: Spinner
    private lateinit var sbSpeed: SeekBar
    private lateinit var tvSpeed: TextView
    private lateinit var tvMediaInfo: TextView
    private lateinit var etScript: EditText
    private lateinit var etPlanJson: EditText
    private lateinit var tvPlanInfo: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnShareGemini: Button
    private lateinit var btnApplyPlan: Button
    private lateinit var btnRender: Button
    private lateinit var btnStop: Button
    private lateinit var btnSave: Button
    private lateinit var btnShare: Button
    private lateinit var videoView: VideoView

    private var photoUris: List<Uri> = emptyList()
    private var videoUri: Uri? = null
    private var musicUri: Uri? = null
    private var planTitle = ""
    private var planTicker = ""
    private var planLines: List<PlanLine> = emptyList()
    private var planShots: List<Shot> = emptyList()
    private var planShotFiles: List<File> = emptyList()
    private var planShotMoves: List<String> = emptyList()
    private var planMusicMood = ""
    private var lastOutput: File? = null
    private var job: Job? = null
    private var busy = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val pickImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            photoUris = uris.take(3)
            videoUri = null
            tvMediaInfo.text = "ပုံ ${photoUris.size} ပုံ ရွေးထားတယ် (video မပါဘူး)" + musicText()
        }
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            videoUri = uri
            photoUris = emptyList()
            tvMediaInfo.text = "Video file ရွေးထားတယ် (ပုံ မပါဘူး)" + musicText()
        }
    }
    private val pickMusic = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            musicUri = uri
            tvMediaInfo.text = mediaBaseText() + musicText()
        }
    }

    private fun mediaBaseText(): String = when {
        videoUri != null -> "Video file ရွေးထားတယ် (ပုံ မပါဘူး)"
        photoUris.isNotEmpty() -> "ပုံ ${photoUris.size} ပုံ ရွေးထားတယ် (video မပါဘူး)"
        else -> "မရွေးရသေးဘူး — အရောင် နောက်ခံနဲ့ လုပ်မယ်"
    }

    private fun musicText(): String = if (musicUri != null) " • music ပါပြီ" else ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
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
            text = "သတင်း → Gemini (အပြင်) → JSON plan → အသံ + 9:16 video"
            setTextColor(color(colMuted))
            textSize = 13f
            gravity = Gravity.CENTER
        })
        root.addView(hint("Key ထည့်စရာ မလိုဘူး — Gemini က အပြင် Gemini app က Google account နဲ့ လုပ်တယ်။"))

        // သတင်း input
        root.addView(title("📰 သတင်း ထည့်ပါ"))
        val newsCard = card()
        etNews = input("မူရင်း သတင်းစာသား ကူးထည့်ပါ — ခေါင်းစဉ်ပဲ ပေးလို့ မရဘူး (fake news ဖြစ်မှာစိုးလို့)", lines = 6)
        newsCard.addView(etNews)
        etSource = input("ရင်းမြစ် နာမည် — ဥပမာ Sky Sports")
        newsCard.addView(etSource)
        etLink = input("သတင်း Link (ရှိရင် — မရှိလဲ ရတယ်)")
        newsCard.addView(etLink)
        root.addView(newsCard)

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

        // ပုံ / video / music
        root.addView(title("🖼️ ပုံ / Video / Music (မထည့်လဲ ရတယ်)"))
        val mediaCard = card()
        tvMediaInfo = TextView(this).apply { text = "မရွေးရသေးဘူး — အရောင် နောက်ခံနဲ့ လုပ်မယ်"; setTextColor(color(colMuted)) }
        mediaCard.addView(tvMediaInfo)
        val btnPhotos = makeButton("📷 ပုံ ၁–၃ ပုံ ရွေးမယ်", colCyan)
        btnPhotos.setOnClickListener { pickImages.launch("image/*") }
        mediaCard.addView(btnPhotos)
        val btnVideo = makeButton("🎬 ဖုန်းထဲက video ရွေးမယ်", colCyan)
        btnVideo.setOnClickListener { pickVideo.launch("video/*") }
        mediaCard.addView(btnVideo)
        val btnMusic = makeButton("🎵 နောက်ခံ music (MP3) ရွေးမယ်", colCyan)
        btnMusic.setOnClickListener { pickMusic.launch("audio/*") }
        mediaCard.addView(btnMusic)
        root.addView(mediaCard)
        root.addView(hint("Music မထည့်ရင် အသံသက်သက်ပဲ ထွက်မယ်။ ထည့်ရင် အသံအောက် တိုးတိုးလေး ရောပေးမယ်။"))

        // Gemini အပြင်ထုတ်မယ်
        root.addView(title("🤖 Gemini အပြင်ထုတ်မယ် (Zippy လို)"))
        val gemCard = card()
        btnShareGemini = makeButton("📤 Gemini ကို ပို့မယ် (prompt + video Share)", colMagenta)
        btnShareGemini.setOnClickListener { shareToGemini() }
        gemCard.addView(btnShareGemini)
        gemCard.addView(hint("Prompt ကို auto-copy လုပ်ပေးမယ်။ Gemini app မှာ Paste → JSON plan ပြန်ပေးမယ်။ Video ရွေးထားရင် video file ကိုပါ တွဲပို့မယ်။"))
        root.addView(gemCard)

        // Plan JSON ကူးထည့်မယ်
        root.addView(title("📋 Plan JSON ကူးထည့်မယ်"))
        etPlanJson = input("Gemini က ပြန်ပေးတဲ့ JSON ကို ဒီမှာ ကူးထည့်ပါ", lines = 6)
        root.addView(etPlanJson)
        btnApplyPlan = makeButton("📋 Plan ဖတ်မယ်", colMagenta)
        btnApplyPlan.setOnClickListener { applyPlan() }
        root.addView(btnApplyPlan)
        tvPlanInfo = TextView(this).apply { setTextColor(color(colMuted)); textSize = 13f }
        root.addView(tvPlanInfo)

        // Script
        root.addView(title("📝 Script"))
        etScript = input("Plan ဖတ်ပြီးရင် script ဒီမှာ ပေါ်လာမယ် — ဖတ်ပြီး ပြင်လို့ရတယ် (တစ်ကြောင်းချင်း = အသံ တစ်ပိုင်း)", lines = 8)
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
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(270), dp(480)).apply {
                topMargin = dp(10)
                gravity = Gravity.CENTER
            }
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
        btnShareGemini.isEnabled = !b
        btnApplyPlan.isEnabled = !b
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

    // ---------- Gemini အပြင်ထုတ်မယ် ----------

    /** Gemini app ကို ပို့မယ့် prompt — ကိုယ်ပိုင် စာသား (Zippy ရဲ့ prompt အတိအကျ မဟုတ်)။ */
    private fun buildExternalPrompt(news: String, source: String, link: String, dur: Int): String {
        val lineCount = when (dur) {
            30 -> "၂-၃ shots"
            90 -> "၆-၈ shots"
            else -> "၄-၅ shots"
        }
        val template = listOf("Classic", "Breaking", "Sport")[spTemplate.selectedItemPosition]
        val voiceName = if (spVoice.selectedItemPosition == 0) "male" else "female"
        return """
You are a Burmese news video planner. Read the source news text below and return ONLY a JSON plan for a vertical news video. No explanation, no markdown fence — JSON only.

Rules:
- Use ONLY facts from the source text. Never invent new facts, names, scores or quotes (no fake news).
- Write all narration in spoken Burmese. Start with a 1-line hook, then the main points, then a 1-line ending.
- Total $lineCount (video about $dur seconds). Each shot has 1-2 short spoken sentences.
- Template: $template. Voice: $voiceName. Speed is 1.2x.
- Each shot: "text" (narration), "move" (one of static / zoom / pan), "image" (a Wikimedia Commons image URL that matches the shot, or empty), "credit" (the image credit, or empty).
- "music_mood": one word for background music mood (e.g. serious, upbeat, tense, calm).
- "ticker": a short ticker line. "sources": list the source name(s).

JSON shape:
{"title": "short title", "ticker": "ticker line", "template": "$template", "voice": "$voiceName", "music_mood": "serious", "shots": [{"text": "narration", "move": "static", "image": "https://upload.wikimedia.org/...", "credit": "Photo: ..."}, {"text": "next narration", "move": "zoom", "image": "", "credit": ""}], "sources": ["$source"]}

Source news text:
$news
${if (link.isNotBlank()) "Source link: $link" else ""}
""".trimIndent()
    }

    private fun shareToGemini() {
        val news = etNews.text.toString().trim()
        if (news.length < 20) {
            showError("သတင်း နည်းနေတယ်", "မူရင်း သတင်းစာသား ကူးထည့်ပါ — ခေါင်းစဉ်ပဲ ပေးလို့ မရဘူး")
            return
        }
        if (busy) return
        setBusy(true, "Gemini ပို့ဖို့ ပြင်နေတယ်...")
        job = lifecycleScope.launch {
            try {
                val prompt = buildExternalPrompt(
                    news,
                    etSource.text.toString().trim(),
                    etLink.text.toString().trim(),
                    durationSec()
                )
                // Prompt ကို clipboard မှာ auto-copy လုပ်ပေးတယ် (Zippy လို)
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("DS News prompt", prompt))

                // Video / ပုံ ရွေးထားရင် file အဖြစ် ကူးပြီး တွဲပို့မယ်
                val shareUris = withContext(Dispatchers.IO) {
                    val dir = File(cacheDir, "share_${System.currentTimeMillis()}").apply { mkdirs() }
                    val list = mutableListOf<Uri>()
                    videoUri?.let { list.add(FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", copyUriToFile(it, File(dir, "video.mp4")))) }
                    if (videoUri == null) {
                        photoUris.forEachIndexed { i, uri ->
                            list.add(FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", copyUriToFile(uri, File(dir, "photo_$i.jpg"))))
                        }
                    }
                    list
                }
                val intent = if (shareUris.isEmpty()) {
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, prompt)
                    }
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "*/*"
                        putExtra(Intent.EXTRA_TEXT, prompt)
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(shareUris))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                startActivity(Intent.createChooser(intent, "Gemini ကို ပို့မယ် — Gemini app ရွေးပါ"))
                tvStatus.text = "Prompt ကူးပြီးပြီ + Share ဖွင့်ပြီးပြီ — Gemini app မှာ Paste လုပ်၊ JSON plan ရရင် အောက်မှာ ကူးထည့်ပါ"
                Toast.makeText(this@MainActivity, "Prompt ကူးပြီးပြီ — Gemini app ရွေးပါ", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                showError("Gemini ပို့မရပါ", e.message ?: "အကြောင်းမသိ error")
            } finally {
                setBusy(false)
            }
        }
    }

    // ---------- Plan JSON ဖတ်မယ် ----------

    /** Gemini အဖြေထဲက ```json ... ``` fence ကို ဖြုတ်ပြီး JSON စာသား သက်သက် ထုတ်ပေးတယ်။ */
    private fun extractJson(raw: String): String {
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

    private fun applyPlan() {
        val raw = etPlanJson.text.toString().trim()
        if (raw.length < 10) {
            showError("JSON မရှိသေးဘူး", "အရင် \"Gemini ကို ပို့မယ်\" နှိပ်ပြီး Gemini က ပြန်ပေးတဲ့ JSON ကို ဒီမှာ ကူးထည့်ပါ")
            return
        }
        if (busy) return
        setBusy(true, "Plan ဖတ်နေတယ်...")
        job = lifecycleScope.launch {
            try {
                val plan = JSONObject(extractJson(raw))
                planTitle = plan.optString("title")
                planTicker = plan.optString("ticker")
                planMusicMood = plan.optString("music_mood")

                // Template / အသံ ကို JSON အတိုင်း ရွေးပေးတယ်
                val tpl = plan.optString("template")
                listOf("Classic", "Breaking", "Sport").indexOfFirst { it.equals(tpl, ignoreCase = true) }
                    .takeIf { it >= 0 }?.let { spTemplate.setSelection(it) }
                val v = plan.optString("voice")
                if (v.contains("female", ignoreCase = true)) spVoice.setSelection(1)
                else if (v.contains("male", ignoreCase = true)) spVoice.setSelection(0)

                // Shots ကို အရင် ယူတယ်၊ မပါရင် lines အဟောင်းပုံစံကို သုံးတယ်
                val shots = mutableListOf<Shot>()
                val shotsArr = plan.optJSONArray("shots")
                if (shotsArr != null) {
                    for (i in 0 until shotsArr.length()) {
                        val o = shotsArr.getJSONObject(i)
                        val t = o.optString("text").trim()
                        if (t.isNotEmpty()) shots.add(
                            Shot(t, o.optString("image"), o.optString("credit"), o.optString("move").ifBlank { "static" })
                        )
                    }
                } else {
                    val arr = plan.optJSONArray("lines")
                        ?: throw IOException("JSON မှာ shots / lines မပါဘူး")
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val t = o.optString("text").trim()
                        if (t.isNotEmpty()) shots.add(Shot(t, "", "", "static"))
                    }
                }
                if (shots.isEmpty()) throw IOException("Plan မှာ စာသား မပါဘူး")
                planShots = shots
                planLines = shots.mapIndexed { i, s -> PlanLine(s.text, i) }

                // Web ပုံတွေ ဒေါင်းတယ် (ရရင် သုံးမယ်၊ မရရင် ကျော်မယ် — plan မပျက်စေနဲ့)
                val shotDir = File(cacheDir, "plan_shots").apply { mkdirs() }
                val files = mutableListOf<File>()
                val moves = mutableListOf<String>()
                val credits = mutableListOf<String>()
                withContext(Dispatchers.IO) {
                    shots.forEachIndexed { i, s ->
                        moves.add(s.move)
                        if (s.credit.isNotBlank()) credits.add(s.credit)
                        if (s.imageUrl.startsWith("http")) {
                            try {
                                val req = Request.Builder().url(s.imageUrl).build()
                                http.newCall(req).execute().use { r ->
                                    if (r.isSuccessful) {
                                        val f = File(shotDir, "shot_$i.jpg")
                                        r.body?.byteStream()?.use { input -> FileOutputStream(f).use { out -> input.copyTo(out) } }
                                        if (f.length() > 0) files.add(f) else files.add(File(""))
                                    } else files.add(File(""))
                                }
                            } catch (_: Exception) {
                                files.add(File(""))
                            }
                        } else files.add(File(""))
                    }
                }
                // ဒေါင်းရတဲ့ ပုံပဲ ယူတယ် — အလွတ် slot တွေကို ဖယ်တယ် (moves လည်း အတူတူ ညှိတယ်)
                val keptFiles = mutableListOf<File>()
                val keptMoves = mutableListOf<String>()
                files.forEachIndexed { i, f ->
                    if (f.path.isNotEmpty() && f.exists() && f.length() > 0) {
                        keptFiles.add(f)
                        keptMoves.add(moves[i])
                    }
                }
                planShotFiles = keptFiles
                planShotMoves = keptMoves

                etScript.setText(shots.joinToString("\n") { it.text })
                val creditNote = if (credits.isNotEmpty()) "\nပုံ credit: ${credits.take(3).joinToString(" • ")}" else ""
                tvPlanInfo.text = "ခေါင်းစဉ်: $planTitle\nTicker: $planTicker\nShots ${shots.size} ခု • Web ပုံ ${keptFiles.size} ပုံ ရပြီ • Music: ${planMusicMood.ifBlank { "—" }}$creditNote\nအသံ ${shots.size} ပိုင်း — ပြင်ပြီးမှ \"Video လုပ်မယ်\" နှိပ်ပါ"
                tvStatus.text = "Plan ရပြီ — ဖတ်ပြီး ပြင်လို့ရတယ်"
            } catch (e: Exception) {
                showError("Plan ဖတ်မရပါ", "${e.message}\n\nGemini က JSON သက်သက် ပြန်ပေးလား စစ်ပါ — စာသား အပို ပါရင် ဖြုတ်ပြီး ပြန်ကူးပါ။")
                tvStatus.text = "Plan မရသေးဘူး"
            } finally {
                setBusy(false)
            }
        }
    }

    // ---------- Render (TTS + FFmpeg) ----------

    private fun renderVideo() {
        val edited = etScript.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (edited.isEmpty()) {
            showError("Script မရှိသေးဘူး", "အရင် \"Gemini ကို ပို့မယ်\" → JSON ကူး → \"Plan ဖတ်မယ်\" လုပ်ပါ")
            return
        }
        if (busy) return
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

                // ပုံ / video / music ကို cache file အဖြစ် ကူးတယ် (FFmpeg က local file နဲ့ လုပ်မှ စိတ်ချရတယ်)
                // + FFmpeg render က blocking ဖြစ်လို့ IO thread မှာ လုပ်တယ် (UI freeze မဖြစ်အောင်)
                val (photoFiles, videoFile, musicFile, moves) = withContext(Dispatchers.IO) {
                    // Web ပုံ (plan) အရင်၊ ပြီးမှ user ပုံ
                    val pf = mutableListOf<File>()
                    val mv = mutableListOf<String>()
                    planShotFiles.forEachIndexed { i, f ->
                        pf.add(copyFileTo(f, File(workDir, "shot_$i.jpg")))
                        mv.add(planShotMoves.getOrElse(i) { "static" })
                    }
                    photoUris.forEachIndexed { i, uri ->
                        pf.add(copyUriToFile(uri, File(workDir, "photo_$i.jpg")))
                        mv.add("static")
                    }
                    val vf = videoUri?.let { copyUriToFile(it, File(workDir, "user_video.mp4")) }
                    val mf = musicUri?.let { copyUriToFile(it, File(workDir, "music.mp3")) }
                    Quad(pf, vf, mf, mv)
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
                        shotMoves = moves,
                        musicFile = musicFile,
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

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

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

    private fun copyFileTo(src: File, out: File): File {
        src.inputStream().use { input -> FileOutputStream(out).use { output -> input.copyTo(output) } }
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
