package com.example.dsnews

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * DS News Tool — FFmpeg နဲ့ 9:16 news video ပေါင်းတာ (DS Recap Tool ရဲ့ VideoMerger.kt ပုံစံအတိုင်း)။
 *
 * စာတန်းထိုး / ခေါင်းစဉ် / ticker ကို drawtext နဲ့ မလုပ်ဘူး — ဖုန်းထဲမှာ Bitmap PNG အဖြစ်
 * အရင် ဆွဲပြီး FFmpeg overlay နဲ့ ကပ်တယ်။ အဲဒီတော့ မြန်မာ font ပြဿနာ မရှိဘူး
 * (Recap လည်း စာတန်းထိုးကို ဒီနည်းနဲ့ပဲ လုပ်တယ်)။
 *
 * V2 အသစ်:
 *  - shotMoves — ပုံတစ်ပုံချင်း move (static / zoom / pan) ကို zoompan နဲ့ လုပ်တယ်။
 *    static ဆိုရင် အရင်အတိုင်း scale/crop slideshow ပဲ။
 *  - musicFile — နောက်ခံ music (MP3) ပါရင် အသံအောက် 15% အသံနဲ့ ရောပေးတယ် (amix)။
 *
 * Pass တွေ (တစ်ခုချင်း ရိုးရိုးလုပ်တယ် — တစ်ခုခု ပျက်ရင် ဘယ် pass လဲ သိရတယ်):
 *  1. base video — ပုံ slideshow / user video / အရောင် နောက်ခံ (720x1280, 30fps, အသံမပါ)
 *  2. အသံ + ခေါင်းစဉ် (0–3.5s) + watermark + ticker bar ထည့်တယ်
 *  3. စာတန်းထိုး PNG တွေ တစ်သုတ် (အများဆုံး ၈ ခု) ချင်း ကပ်တယ်
 *  ၄. အထွက် — H.264 + AAC, Movies/DS News ထဲ သိမ်းဖို့ MainActivity က ရွှေ့မယ်
 */
object NewsRenderer {

    data class Line(
        val text: String,
        val audioFile: File,
        val startSec: Double,
        val endSec: Double,
        val photoIndex: Int // -1 = နောက်ခံပဲ
    )

    data class Template(val name: String, val bg: String, val accent: String)

    val TEMPLATES = listOf(
        Template("Classic", "0D1226", "00E5FF"),
        Template("Breaking", "1A0505", "FF2D2D"),
        Template("Sport", "052012", "22C55E")
    )

    const val W = 720
    const val H = 1280
    private const val MAX_CUES_PER_PASS = 8

    suspend fun render(
        context: Context,
        workDir: File,
        lines: List<Line>,
        totalDurSec: Double,
        photos: List<File>,
        userVideo: File?,
        template: Template,
        title: String,
        ticker: String,
        source: String,
        shotMoves: List<String> = emptyList(),
        musicFile: File? = null,
        onProgress: (String) -> Unit
    ): File {
        workDir.mkdirs()
        val total = totalDurSec.coerceAtLeast(1.0)

        // --- အသံ ဆက်တယ် (MP3 အပိုင်းတွေ → AAC တစ်ပုဒ်) ---
        onProgress("အသံ ဆက်နေတယ်...")
        val audioFile = File(workDir, "audio.m4a")
        val listFile = File(workDir, "audio_list.txt")
        listFile.writeText(lines.joinToString("\n") {
            "file '${it.audioFile.absolutePath.replace("'", "'\\''")}'"
        })
        runFfmpeg(arrayOf(
            "-y", "-hide_banner", "-loglevel", "error",
            "-f", "concat", "-safe", "0", "-i", listFile.absolutePath,
            "-c:a", "aac", "-b:a", "128k", audioFile.absolutePath
        ), "အသံ ဆက်မရပါ")

        // --- နောက်ခံ music ပါရင် အသံအောက် တိုးတိုးလေး ရောတယ် ---
        var finalAudio = audioFile
        if (musicFile != null && musicFile.exists() && musicFile.length() > 0) {
            onProgress("Music ရောနေတယ်...")
            val mixed = File(workDir, "audio_mixed.m4a")
            runFfmpeg(arrayOf(
                "-y", "-hide_banner", "-loglevel", "error",
                "-i", audioFile.absolutePath,
                "-stream_loop", "-1", "-i", musicFile.absolutePath,
                "-filter_complex", "[1:a]volume=0.15[m];[0:a][m]amix=inputs=2:duration=first:dropout_transition=0[aout]",
                "-map", "[aout]",
                "-c:a", "aac", "-b:a", "128k", "-t", fmt(total), mixed.absolutePath
            ), "Music ရောမရပါ")
            finalAudio = mixed
        }

        // --- Pass 1: base video ---
        onProgress("Video အခြေခံ တည်ဆောက်နေတယ်...")
        val base = File(workDir, "base.mp4")
        when {
            userVideo != null -> runFfmpeg(arrayOf(
                "-y", "-hide_banner", "-loglevel", "error",
                "-stream_loop", "-1", "-i", userVideo.absolutePath,
                "-t", fmt(total),
                "-vf", "scale=720:1280:force_original_aspect_ratio=increase,crop=720:1280,setsar=1,fps=30",
                "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                "-pix_fmt", "yuv420p", "-r", "30", base.absolutePath
            ), "Video အခြေခံ လုပ်မရပါ")
            photos.isNotEmpty() -> {
                val per = total / photos.size
                val frames = (per * 30).toInt().coerceAtLeast(15)
                val args = mutableListOf<String>()
                args += listOf("-y", "-hide_banner", "-loglevel", "error")
                photos.forEachIndexed { i, photo ->
                    val move = shotMoves.getOrElse(i) { "static" }.lowercase(Locale.US)
                    if (move == "zoom" || move == "pan" || move == "left" || move == "right") {
                        args += listOf("-i", photo.absolutePath)
                    } else {
                        args += listOf("-loop", "1", "-t", fmt(per), "-i", photo.absolutePath)
                    }
                }
                val fc = StringBuilder()
                photos.indices.forEach { i ->
                    val move = shotMoves.getOrElse(i) { "static" }.lowercase(Locale.US)
                    when (move) {
                        "zoom" -> fc.append("[$i:v]zoompan=z='min(zoom+0.0012,1.12)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=$frames:s=720x1280:fps=30,setsar=1[v$i];")
                        "pan", "left" -> fc.append("[$i:v]zoompan=z='1.12':x='(iw-iw/zoom)*on/$frames':y='ih/2-(ih/zoom/2)':d=$frames:s=720x1280:fps=30,setsar=1[v$i];")
                        "right" -> fc.append("[$i:v]zoompan=z='1.12':x='(iw-iw/zoom)*(1-on/$frames)':y='ih/2-(ih/zoom/2)':d=$frames:s=720x1280:fps=30,setsar=1[v$i];")
                        else -> fc.append("[$i:v]scale=720:1280:force_original_aspect_ratio=increase,crop=720:1280,setsar=1,fps=30,trim=0:${fmt(per)},setpts=PTS-STARTPTS[v$i];")
                    }
                }
                photos.indices.forEach { fc.append("[v$it]") }
                fc.append("concat=n=${photos.size}:v=1:a=0[vout]")
                args += listOf("-filter_complex", fc.toString(), "-map", "[vout]",
                    "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                    "-pix_fmt", "yuv420p", "-r", "30", base.absolutePath)
                runFfmpeg(args.toTypedArray(), "ပုံ slideshow လုပ်မရပါ")
            }
            else -> runFfmpeg(arrayOf(
                "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "color=c=0x${template.bg}:s=720x1280:r=30:d=${fmt(total)}",
                "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                "-pix_fmt", "yuv420p", "-r", "30", base.absolutePath
            ), "နောက်ခံ video လုပ်မရပါ")
        }

        // --- PNG တွေ ဆွဲတယ် ---
        val titlePng = renderTextPng(File(workDir, "title.png"), title, 680, 64, "#FFFFFF", "#05070F")
        val wmPng = renderTextPng(File(workDir, "wm.png"), "DS NEWS", 200, 34, "#FFFFFF", "#05070F")
        val tickerText = if (source.isNotBlank()) "$ticker  •  ရင်းမြစ်: $source" else ticker
        val tickerPng = renderBarPng(File(workDir, "ticker.png"), tickerText, template.accent)

        // --- Pass 2: အသံ + ခေါင်းစဉ် + watermark + ticker ---
        onProgress("အသံ + ခေါင်းစဉ် + ticker ပေါင်းနေတယ်...")
        val withAudio = File(workDir, "with_audio.mp4")
        runFfmpeg(arrayOf(
            "-y", "-hide_banner", "-loglevel", "error",
            "-i", base.absolutePath, "-i", finalAudio.absolutePath,
            "-i", titlePng.absolutePath, "-i", wmPng.absolutePath, "-i", tickerPng.absolutePath,
            "-filter_complex",
            "[0:v][2:v]overlay=(W-w)/2:110:enable='between(t,0,3.5)'[v1];" +
            "[v1][3:v]overlay=W-w-16:16[v2];" +
            "[v2][4:v]overlay=0:1216[vout]",
            "-map", "[vout]", "-map", "1:a",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
            "-pix_fmt", "yuv420p", "-r", "30",
            "-c:a", "aac", "-b:a", "128k", "-t", fmt(total), withAudio.absolutePath
        ), "အသံ + ခေါင်းစဉ် ပေါင်းမရပါ")

        // --- Pass 3: စာတန်းထိုး (၈ ခုတစ်သုတ်) ---
        var current = withAudio
        var pass = 0
        lines.chunked(MAX_CUES_PER_PASS).forEach { batch ->
            pass++
            onProgress("စာတန်းထိုး ထည့်နေတယ် ($pass)...")
            val pngs = batch.mapIndexed { i, line ->
                renderTextPng(File(workDir, "sub_${pass}_$i.png"), line.text, 680, 52, "#FFFFFF", "#000000")
            }
            val args = mutableListOf<String>()
            args += listOf("-y", "-hide_banner", "-loglevel", "error", "-i", current.absolutePath)
            pngs.forEach { args += listOf("-i", it.absolutePath) }
            val fc = StringBuilder()
            var prev = "0:v"
            batch.forEachIndexed { i, line ->
                val out = if (i == batch.lastIndex) "vout" else "v$i"
                fc.append("[$prev][${i + 1}:v]overlay=(W-w)/2:980:enable='between(t,${fmt(line.startSec)},${fmt(line.endSec)})'[$out];")
                prev = out
            }
            val outFile = File(workDir, "sub_$pass.mp4")
            args += listOf("-filter_complex", fc.toString().removeSuffix(";"),
                "-map", "[vout]", "-map", "0:a",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                "-pix_fmt", "yuv420p", "-r", "30",
                "-c:a", "copy", "-t", fmt(total), outFile.absolutePath)
            runFfmpeg(args.toTypedArray(), "စာတန်းထိုး ထည့်မရပါ (အသုတ် $pass)")
            current = outFile
        }

        val final = File(workDir, "DS_news_final.mp4")
        if (current != final) current.copyTo(final, overwrite = true)
        return final
    }

    private fun runFfmpeg(args: Array<String>, errLabel: String) {
        val s = FFmpegKit.executeWithArguments(args)
        if (!ReturnCode.isSuccess(s.returnCode)) {
            val log = (s.allLogsAsString ?: s.output ?: "").takeLast(1200)
            throw IOException("$errLabel\n\nFFmpeg:\n$log")
        }
    }

    // FFmpeg ကိန်းဂဏန်း decimal ကို dot (.) နဲ့ပဲ လက်ခံတယ် — ဖုန်း locale က comma (,) သုံးရင်
    // filter ပျက်မယ်၊ ဒါကြောင့် Locale.US အတိအကျ သုံးတယ်။
    private fun fmt(v: Double): String = "%.3f".format(Locale.US, v)

    /** စာသားကို အဖြူ + အနက် outline နဲ့ PNG ဆွဲတယ်။ စာရှည်ရင် အလိုအလျောက် ၂-၃ ကြောင်း ခွဲတယ်။ */
    fun renderTextPng(out: File, text: String, width: Int, textSize: Int, fg: String, stroke: String): File {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(fg)
            this.textSize = textSize.toFloat()
            typeface = Typeface.DEFAULT_BOLD
        }
        val strokePaint = Paint(paint).apply {
            color = Color.parseColor(stroke)
            style = Paint.Style.STROKE
            strokeWidth = textSize / 6f
            strokeJoin = Paint.Join.ROUND
        }
        // စာကြောင်း ခွဲတယ်
        val lines = mutableListOf<String>()
        var rest = text.trim()
        while (rest.isNotEmpty()) {
            var cut = rest.length
            while (cut > 0 && paint.measureText(rest.substring(0, cut)) > width) cut--
            if (cut == 0) cut = 1
            // စာလုံးအလယ် မဖြတ်ရအောင် နောက်ဆုံး space ကို ရှာတယ်
            if (cut < rest.length) {
                val sp = rest.lastIndexOf(' ', cut)
                if (sp > 0) cut = sp
            }
            lines.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
            if (lines.size >= 4) break
        }
        if (lines.isEmpty()) lines.add(" ")
        val lineH = (textSize * 1.35).toInt()
        val h = lineH * lines.size + 24
        val bmp = Bitmap.createBitmap(width + 24, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        lines.forEachIndexed { i, ln ->
            val x = (bmp.width - paint.measureText(ln)) / 2f
            val y = 12f + textSize + i * lineH
            c.drawText(ln, x, y, strokePaint)
            c.drawText(ln, x, y, paint)
        }
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return out
    }

    /** အောက်က ticker bar — အရောင် bar ပေါ်မှာ အဖြူ စာသား (V1 မှာ ရွေ့မနေဘူး၊ အငြိမ်ပဲ)။ */
    private fun renderBarPng(out: File, text: String, accentHex: String): File {
        val h = 64
        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.parseColor("#$accentHex"))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
        }
        var t = text.trim()
        while (paint.measureText(t) > W - 24 && t.length > 1) t = t.dropLast(1)
        c.drawText(t, 12f, 44f, paint)
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return out
    }
}
