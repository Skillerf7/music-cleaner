package com.ultra.musiccleaner

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.*
import androidx.documentfile.provider.DocumentFile
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt

class Track(val doc: DocumentFile, val parent: DocumentFile, val name: String, val size: Long) {
    var hash: String? = null
    var env: FloatArray? = null
    var durMs: Long = 0
}

class Group(val title: String, val tracks: MutableList<Track>)

class MainActivity : Activity() {
    private val QDIR = "_Quarantaene"
    private val exts = setOf("mp3", "wav", "flac", "m4a", "ogg", "opus", "aac", "wma")
    private var root: DocumentFile? = null
    private var player: MediaPlayer? = null
    private var cur: List<Group> = emptyList()
    private lateinit var status: TextView
    private lateinit var list: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(30, 70, 30, 30)
        val title = TextView(this)
        title.text = "🎵 Music Cleaner"
        title.textSize = 24f
        status = TextView(this)
        status.text = "Wähle einen Ordner"
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        col.addView(title)
        col.addView(btn("📂 Ordner auswählen") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 1)
        })
        col.addView(btn("🔍 Scannen & Duplikate finden") { scan() })
        col.addView(btn("🗑️ Quarantäne endgültig leeren") { emptyQ() })
        col.addView(status)
        col.addView(list)
        sv.addView(col)
        setContentView(sv)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data
        if (req == 1 && res == RESULT_OK && uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            root = DocumentFile.fromTreeUri(this, uri)
            status.text = "Ordner: ${root?.name}"
        }
    }

    private fun btn(t: String, a: () -> Unit): Button {
        val bt = Button(this)
        bt.text = t
        bt.setOnClickListener { a() }
        return bt
    }

    private fun ui(s: String) = runOnUiThread { status.text = s }

    private fun collect(dir: DocumentFile, out: MutableList<Track>) {
        for (f in dir.listFiles()) {
            if (f.isDirectory) {
                if (f.name != QDIR) collect(f, out)
            } else {
                val n = f.name ?: continue
                if (n.substringAfterLast('.', "").lowercase() in exts) out.add(Track(f, dir, n, f.length()))
            }
        }
    }

    private fun sha(d: DocumentFile): String {
        val md = MessageDigest.getInstance("SHA-256")
        contentResolver.openInputStream(d.uri)?.use { s ->
            val buf = ByteArray(65536)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun analyze(t: Track) {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(this, t.doc.uri, null)
            var idx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { idx = i; fmt = f; break }
            }
            val f = fmt ?: return
            ex.selectTrack(idx)
            t.durMs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1000 else 0
            val sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (t.durMs > 45000) ex.seekTo(15_000_000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val codec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(f, null, null, 0)
            codec.start()
            val win = sr / 2
            val env = FloatArray(40)
            var frames = 0L
            val total = win.toLong() * 40
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            while (!outDone && frames < total) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10000)
                if (oi >= 0) {
                    val sb = codec.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val n = info.size / 2 / ch
                    for (fr in 0 until n) {
                        var s = 0f
                        for (c in 0 until ch) s += abs(sb.get(fr * ch + c).toInt()) / 32768f
                        val w = (frames / win).toInt()
                        if (w < 40) env[w] += s / ch
                        frames++
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                }
            }
            codec.stop()
            codec.release()
            if (frames > win * 10L) t.env = env
        } catch (e: Exception) {
        } finally {
            ex.release()
        }
    }

    private fun corr(a: FloatArray, b: FloatArray): Float {
        val ma = a.average().toFloat()
        val mb = b.average().toFloat()
        var n = 0f; var da = 0f; var db = 0f
        for (i in a.indices) {
            val x = a[i] - ma; val y = b[i] - mb
            n += x * y; da += x * x; db += y * y
        }
        return if (da == 0f || db == 0f) 0f else n / sqrt(da * db)
    }

    private fun norm(n: String) = n.substringBeforeLast('.').lowercase()
        .replace(Regex("\\(\\d+\\)|copy|kopie|v\\d+|version\\s*\\d+"), "")
        .replace(Regex("[^a-z]"), "")

    private fun scan() {
        val r = root ?: run { status.text = "Erst Ordner wählen"; return }
        list.removeAllViews()
        Thread {
            try {
                val all = ArrayList<Track>()
                collect(r, all)
                ui("${all.size} Audiodateien, prüfe Hashes…")
                val groups = ArrayList<Group>()
                val skip = HashSet<Track>()
                for ((_, l) in all.groupBy { it.size }) {
                    if (l.size < 2) continue
                    l.forEach { it.hash = sha(it.doc) }
                    for ((_, h) in l.groupBy { it.hash }) {
                        if (h.size > 1) { groups.add(Group("Exakte Kopie", h.toMutableList())); skip.addAll(h.drop(1)) }
                    }
                }
                val rest = all.filter { it !in skip }
                rest.forEachIndexed { i, t -> ui("Analysiere ${i + 1}/${rest.size}: ${t.name}"); analyze(t) }
                val shortT = rest.filter { it.durMs in 1..29999 }
                val long = rest.filter { it.env != null && it.durMs >= 30000 }
                val done = HashSet<Track>()
                for (i in long.indices) {
                    if (long[i] in done) continue
                    val g = mutableListOf(long[i])
                    for (j in i + 1 until long.size) {
                        if (long[j] !in done && abs(long[i].durMs - long[j].durMs) < 1500 &&
                            corr(long[i].env!!, long[j].env!!) > 0.97f) g.add(long[j])
                    }
                    if (g.size > 1) { groups.add(Group("Audio-Duplikat", g)); done.addAll(g) }
                }
                val left = long.filter { it !in done }
                val seen = HashSet<Track>()
                for (i in left.indices) {
                    if (left[i] in seen) continue
                    val g = mutableListOf(left[i])
                    for (j in i + 1 until left.size) {
                        if (left[j] in seen) continue
                        val nn = norm(left[i].name)
                        val sameName = nn.length > 3 && nn == norm(left[j].name)
                        val close = abs(left[i].durMs - left[j].durMs) < 20000 &&
                            corr(left[i].env!!, left[j].env!!) > 0.85f
                        if (sameName || close) g.add(left[j])
                    }
                    if (g.size > 1) { groups.add(Group("Ähnliche Versionen", g)); seen.addAll(g) }
                }
                if (shortT.isNotEmpty()) groups.add(Group("Kurze Schnipsel (unter 30 s)", shortT.toMutableList()))
                cur = groups
                runOnUiThread { show() }
            } catch (e: Exception) {
                ui("Fehler: ${e.message}")
            }
        }.start()
    }

    private fun show() {
        list.removeAllViews()
        val vis = cur.filter { if (it.title.startsWith("Kurze")) it.tracks.isNotEmpty() else it.tracks.size > 1 }
        status.text = "${vis.size} Gruppen gefunden"
        for (g in vis) {
            val h = TextView(this)
            h.text = "▼ ${g.title}"
            h.textSize = 18f
            h.setPadding(0, 50, 0, 8)
            list.addView(h)
            for (t in g.tracks.toList()) {
                val tv = TextView(this)
                tv.text = "${t.name}\n${t.size / 1024} KB · ${t.durMs / 1000} s"
                val bar = LinearLayout(this)
                bar.addView(btn("▶") { play(t) })
                bar.addView(btn("✅ Behalten") { keep(g, t) })
                bar.addView(btn("📦") { one(g, t) })
                list.addView(tv)
                list.addView(bar)
            }
        }
    }

    private fun play(t: Track) {
        try {
            player?.release()
            val p = MediaPlayer()
            p.setDataSource(this, t.doc.uri)
            p.prepare()
            p.start()
            player = p
        } catch (e: Exception) {
            ui("Vorschau nicht möglich")
        }
    }

    private fun keep(g: Group, t: Track) {
        if (g.title.startsWith("Kurze")) { g.tracks.remove(t); show(); return }
        val others = g.tracks.filter { it !== t }
        status.text = "Verschiebe ${others.size} Datei(en)…"
        Thread {
            others.forEach { quarantine(it) }
            g.tracks.clear()
            runOnUiThread { show() }
        }.start()
    }

    private fun one(g: Group, t: Track) {
        Thread {
            quarantine(t)
            g.tracks.remove(t)
            runOnUiThread { show() }
        }.start()
    }

    private fun quarantine(t: Track): Boolean {
        val r = root ?: return false
        val q = r.findFile(QDIR) ?: r.createDirectory(QDIR) ?: return false
        try {
            if (DocumentsContract.moveDocument(contentResolver, t.doc.uri, t.parent.uri, q.uri) != null) return true
        } catch (e: Exception) {
        }
        return try {
            val out = q.createFile("audio/mpeg", t.name) ?: return false
            contentResolver.openInputStream(t.doc.uri)!!.use { i ->
                contentResolver.openOutputStream(out.uri)!!.use { o -> i.copyTo(o) }
            }
            t.doc.delete()
        } catch (e: Exception) {
            false
        }
    }

    private fun emptyQ() {
        val files = root?.findFile(QDIR)?.listFiles() ?: emptyArray<DocumentFile>()
        if (files.isEmpty()) { status.text = "Quarantäne ist leer"; return }
        AlertDialog.Builder(this)
            .setTitle("Endgültig löschen?")
            .setMessage("${files.size} Dateien werden unwiderruflich gelöscht.")
            .setPositiveButton("Löschen") { _, _ ->
                Thread { files.forEach { it.delete() }; ui("Quarantäne geleert") }.start()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    override fun onDestroy() {
        player?.release()
        super.onDestroy()
    }
}
