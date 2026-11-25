package com.openapk.translate

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.openapk.translate.core.ApkProcessor
import com.openapk.translate.core.ArscEditor
import com.openapk.translate.core.SignKeys
import com.openapk.translate.core.SmartTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var txtSource: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtLog: TextView
    private lateinit var scroll: ScrollView
    private lateinit var spFrom: Spinner
    private lateinit var spTo: Spinner
    private lateinit var btnGo: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var resultBox: View

    private var inputs: List<File> = emptyList()
    private var label = "app"
    private var pkgName: String? = null
    private var outputs: List<File> = emptyList()
    private var job: Job? = null

    private data class Lang(val code: String, val name: String) {
        override fun toString() = name
    }

    private val langs: List<Lang> by lazy {
        val ru = Locale("ru")
        TranslateLanguage.getAllLanguages()
            .map { Lang(it, Locale(it).getDisplayName(ru).replaceFirstChar { c -> c.titlecase(ru) } + "  ($it)") }
            .sortedBy { it.name }
    }

    private val latin = setOf(
        "en", "de", "fr", "es", "it", "pt", "nl", "sv", "da", "no", "fi", "pl", "cs", "sk", "sl", "hr",
        "ro", "hu", "tr", "id", "ms", "vi", "tl", "sw", "af", "ca", "gl", "et", "lv", "lt", "sq", "is",
        "ga", "cy", "eo", "ht", "mt",
    )

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadFromUri(uri)
    }

    private val saveApk = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri -> if (uri != null) saveTo(uri) }

    private val saveApks = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) saveTo(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        txtSource = findViewById(R.id.txtSource)
        txtStatus = findViewById(R.id.txtStatus)
        txtLog = findViewById(R.id.txtLog)
        scroll = findViewById(R.id.scroll)
        spFrom = findViewById(R.id.spFrom)
        spTo = findViewById(R.id.spTo)
        btnGo = findViewById(R.id.btnGo)
        progress = findViewById(R.id.progress)
        resultBox = findViewById(R.id.resultBox)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langs)
        spFrom.adapter = adapter
        spTo.adapter = adapter
        spFrom.setSelection(langs.indexOfFirst { it.code == "en" }.coerceAtLeast(0))
        spTo.setSelection(langs.indexOfFirst { it.code == "ru" }.coerceAtLeast(0))

        findViewById<MaterialButton>(R.id.btnFile).setOnClickListener {
            pickFile.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "application/zip", "*/*"))
        }
        findViewById<MaterialButton>(R.id.btnInstalled).setOnClickListener { pickInstalled() }
        btnGo.setOnClickListener { startTranslation() }
        findViewById<MaterialButton>(R.id.btnInstall).setOnClickListener { install() }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            val to = (spTo.selectedItem as Lang).code
            val base = label.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
            if (outputs.size == 1) saveApk.launch("$base-$to.apk") else saveApks.launch("$base-$to.apks")
        }
        findViewById<MaterialButton>(R.id.btnUninstall).setOnClickListener {
            pkgName?.let { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$it"))) }
        }

        InstallEvents.listener = { msg -> runOnUiThread { status(msg); log(msg) } }
        log("Готов. Выбери APK-файл или установленное приложение.")
        log("Перевод идёт на устройстве (Google ML Kit). Языковая модель ~30 МБ скачивается один раз, дальше всё офлайн.")
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        InstallEvents.listener = null
        super.onDestroy()
    }

    private fun handleIntent(i: Intent?) {
        val uri = when (i?.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33)
                i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        } ?: return
        loadFromUri(uri)
    }

    // ---------- input ----------

    private fun resetInputs(): File {
        resultBox.visibility = View.GONE
        outputs = emptyList()
        val dir = File(cacheDir, "input")
        dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    private fun loadFromUri(uri: Uri) {
        if (job?.isActive == true) return
        val dir = resetInputs()
        status("Читаю файл…")
        lifecycleScope.launch {
            try {
                val (files, name) = withContext(Dispatchers.IO) {
                    val src = File(dir, "source.bin")
                    contentResolver.openInputStream(uri)!!.use { inp -> src.outputStream().use { inp.copyTo(it) } }
                    val list = ZipFile(src).use { z ->
                        if (z.getEntry("AndroidManifest.xml") != null) null
                        else z.entries().toList().filter { !it.isDirectory && it.name.endsWith(".apk", true) }.mapIndexed { n, e ->
                            val f = File(dir, if (e.name.substringAfterLast('/') == "base.apk") "base.apk" else "part$n.apk")
                            z.getInputStream(e).use { inp -> f.outputStream().use { inp.copyTo(it) } }
                            f
                        }
                    }
                    val files = if (list == null) {
                        val f = File(dir, "base.apk"); src.renameTo(f); listOf(f)
                    } else {
                        src.delete(); list
                    }
                    if (files.isEmpty()) throw IllegalArgumentException("в архиве нет APK")
                    val base = baseOf(files)
                    val info = packageManager.getPackageArchiveInfo(base.path, 0)
                    var nm = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "app"
                    info?.applicationInfo?.let { ai ->
                        ai.sourceDir = base.path; ai.publicSourceDir = base.path
                        nm = ai.loadLabel(packageManager).toString()
                    }
                    pkgName = info?.packageName
                    files to nm
                }
                setInputs(files, name)
            } catch (e: Exception) {
                status("❌ Не получилось открыть файл: ${e.message}")
                log("Ошибка: $e")
            }
        }
    }

    private fun baseOf(files: List<File>) =
        files.firstOrNull { it.name == "base.apk" } ?: files.maxBy { it.length() }

    private fun setInputs(files: List<File>, name: String) {
        inputs = files
        label = name
        val mb = files.sumOf { it.length() } / 1024.0 / 1024.0
        txtSource.text = "✅ $name" + (pkgName?.let { "\n$it" } ?: "") +
            "\n${files.size} APK · %.1f МБ".format(mb)
        btnGo.isEnabled = true
        status("Выбрано: $name")
    }

    private fun pickInstalled() {
        if (job?.isActive == true) return
        status("Загружаю список приложений…")
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(0)
                    .filter { it.packageName != packageName && (it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0) }
                    .map { it to it.loadLabel(packageManager).toString() }
                    .sortedBy { it.second.lowercase() }
            }
            status("")
            showAppDialog(apps)
        }
    }

    private fun showAppDialog(apps: List<Pair<ApplicationInfo, String>>) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 8, 32, 0) }
        val search = EditText(this).apply { hint = "🔍 Поиск" }
        val list = ListView(this)
        box.addView(search)
        box.addView(list, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.6).toInt()))
        var shown = apps
        fun refresh() {
            list.adapter = object : ArrayAdapter<Pair<ApplicationInfo, String>>(this, android.R.layout.simple_list_item_2, android.R.id.text1, shown) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    val v = super.getView(position, convertView, parent)
                    v.findViewById<TextView>(android.R.id.text1).text = shown[position].second
                    v.findViewById<TextView>(android.R.id.text2).text = shown[position].first.packageName
                    return v
                }
            }
        }
        refresh()
        val dlg = AlertDialog.Builder(this).setTitle("Выбери приложение").setView(box)
            .setNegativeButton("Отмена", null).create()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString().trim().lowercase()
                shown = if (q.isEmpty()) apps else apps.filter { it.second.lowercase().contains(q) || it.first.packageName.contains(q) }
                refresh()
            }
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            val (ai, name) = shown[pos]
            dlg.dismiss()
            resetInputs()
            pkgName = ai.packageName
            val files = listOf(File(ai.sourceDir)) + (ai.splitSourceDirs?.map { File(it) } ?: emptyList())
            setInputs(files, name)
        }
        dlg.show()
    }

    // ---------- translate ----------

    private fun startTranslation() {
        if (inputs.isEmpty() || job?.isActive == true) return
        val from = (spFrom.selectedItem as Lang).code
        val to = (spTo.selectedItem as Lang).code
        if (from == to) { status("Языки одинаковые 🙃"); return }
        btnGo.isEnabled = false
        resultBox.visibility = View.GONE
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        txtLog.text = ""
        job = lifecycleScope.launch {
            val translator = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build()
            )
            try {
                status("Готовлю языковую модель $from → $to (первый раз скачивается)…")
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                log("Модель готова ✅")

                status("Ищу строки интерфейса…")
                val editors = withContext(Dispatchers.IO) {
                    inputs.map { f ->
                        try {
                            ApkProcessor.readArsc(f)?.let { ArscEditor(it) }
                        } catch (e: Exception) {
                            log("⚠️ ${f.name}: не разобрал resources.arsc (${e.message}), оставляю как есть"); null
                        }
                    }
                }
                val unique = editors.filterNotNull().flatMap { it.targetTexts.values }.toSet()
                log("Найдено строк: ${unique.size}")
                if (unique.isEmpty()) throw IllegalStateException("в приложении не нашлось строк для перевода")

                val smart = SmartTranslator(from in latin) { s -> translator.translate(s).await() }
                progress.isIndeterminate = false
                progress.max = unique.size
                var lastUi = 0L
                val map = withContext(Dispatchers.Default) {
                    smart.translateAll(unique, parallelism = 4) { done, total, sample ->
                        val now = System.currentTimeMillis()
                        if (now - lastUi > 250 || done == total) {
                            lastUi = now
                            runOnUiThread {
                                progress.progress = done
                                status("Перевожу: $done / $total")
                                if (sample != null) log("• ${sample.first.take(60)} → ${sample.second.take(60)}")
                            }
                        }
                    }
                }
                log("Переведено: ${map.size} из ${unique.size} (остальное — ключи/служебное, не трогаю)")

                progress.isIndeterminate = true
                status("Собираю и подписываю APK…")
                val outs = withContext(Dispatchers.IO) {
                    val outDir = File(cacheDir, "out").apply { deleteRecursively(); mkdirs() }
                    val keys = SignKeys.loadOrCreate(File(filesDir, "keys"))
                    inputs.mapIndexed { i, f ->
                        val ed = editors[i]
                        val newArsc = ed?.let { e ->
                            val idx = e.targetTexts.mapNotNull { (k, s) -> map[s]?.let { k to it } }.toMap()
                            if (idx.isEmpty()) null else e.build(idx)
                        }
                        val outName = if (f.name == "base.apk" || inputs.size == 1) "base.apk" else "split$i.apk"
                        val unsigned = File(outDir, "$outName.tmp")
                        val out = File(outDir, outName)
                        ApkProcessor.rebuild(f, newArsc, unsigned)
                        ApkProcessor.sign(unsigned, out, keys)
                        unsigned.delete()
                        out
                    }
                }
                outputs = outs
                status("🎉 Готово! Можно ставить.")
                log("Важно: подпись отличается от оригинала — перед установкой удали оригинальное приложение (данные приложения удалятся).")
                resultBox.visibility = View.VISIBLE
            } catch (e: Exception) {
                status("❌ Ошибка: ${e.message}")
                log("Ошибка: $e")
            } finally {
                translator.close()
                progress.visibility = View.GONE
                btnGo.isEnabled = true
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // ---------- output ----------

    private fun install() {
        if (outputs.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Разреши установку из этого приложения и нажми ещё раз", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        status("Устанавливаю…")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val pi = packageManager.packageInstaller
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    val id = pi.createSession(params)
                    pi.openSession(id).use { s ->
                        for (f in outputs) {
                            s.openWrite(f.name, 0, f.length()).use { o ->
                                f.inputStream().use { it.copyTo(o, 1 shl 16) }
                                s.fsync(o)
                            }
                        }
                        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                        val pending = PendingIntent.getBroadcast(
                            this@MainActivity, id, Intent(this@MainActivity, InstallReceiver::class.java), flags
                        )
                        s.commit(pending.intentSender)
                    }
                }
            } catch (e: Exception) {
                status("❌ Установка: ${e.message}")
            }
        }
    }

    private fun saveTo(uri: Uri) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)!!.use { o ->
                        if (outputs.size == 1) {
                            outputs[0].inputStream().use { it.copyTo(o) }
                        } else {
                            ZipOutputStream(o).use { z ->
                                for (f in outputs) {
                                    z.putNextEntry(ZipEntry(f.name))
                                    f.inputStream().use { it.copyTo(z) }
                                    z.closeEntry()
                                }
                            }
                        }
                    }
                }
                status("💾 Сохранено")
            } catch (e: Exception) {
                status("❌ Не сохранилось: ${e.message}")
            }
        }
    }

    // ---------- ui helpers ----------

    private fun status(s: String) {
        txtStatus.text = s
    }

    private fun log(s: String) = runOnUiThread {
        val cur = txtLog.text
        txtLog.text = if (cur.length > 20000) cur.subSequence(cur.length - 15000, cur.length).toString() + "\n" + s
        else if (cur.isEmpty()) s else "$cur\n$s"
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
