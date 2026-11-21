package com.imeirepair

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var buttons: List<Button>

    private val prefs by lazy { getSharedPreferences("imei_store", Context.MODE_PRIVATE) }
    private val model: String
        get() = Build.MODEL.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')
    private fun stamp() = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        logView = findViewById(R.id.log)
        scroll = findViewById(R.id.scroll)
        val b1 = findViewById<Button>(R.id.btnCheck)
        val b2 = findViewById<Button>(R.id.btnFix)
        val b3 = findViewById<Button>(R.id.btnWrite)
        buttons = listOf(b1, b2, b3)

        requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 1)

        b1.setOnClickListener { work { checkAndBackup() } }
        b2.setOnClickListener { work { fixNull() } }
        b3.setOnClickListener { askManualImei() }

        log("ماڈل: ${Build.MANUFACTURER} ${Build.MODEL}")
        work {
            log(if (RootShell.isRootAvailable()) "Root: دستیاب ✔"
                else "⚠ Root نہیں ملا۔ یہ ایپ Root کے بغیر کام نہیں کرے گی۔")
        }
    }

    // ---------- UI helpers ----------
    private fun log(msg: String) = runOnUiThread {
        logView.append(msg + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun work(block: () -> Unit) {
        runOnUiThread { buttons.forEach { it.isEnabled = false } }
        Thread {
            try { block() } catch (e: Exception) { log("خرابی: ${e.message}") }
            runOnUiThread { buttons.forEach { it.isEnabled = true } }
        }.start()
    }

    // ---------- Button 1: read IMEI, save, backup NVRAM ----------
    private fun checkAndBackup() {
        log("\n--- IMEI چیک ---")
        val (i1, i2) = ImeiManager.readAll(this)
        log("IMEI 1: ${describe(i1)}")
        log("IMEI 2: ${describe(i2)}")

        val v1 = ImeiManager.isValid(i1)
        val v2 = i2 == null || ImeiManager.isValid(i2)
        if (!v1 || !v2) {
            log("⚠ IMEI Null/غلط ہے۔ بیک اپ نہیں لیا گیا تاکہ اچھا بیک اپ خراب نہ ہو۔ 'Null IMEI درست کرو' دبائیں۔")
            return
        }

        // save valid IMEIs (never overwritten by null values)
        prefs.edit().putString("imei1", i1).apply {
            if (i2 != null && ImeiManager.isValid(i2)) putString("imei2", i2)
        }.apply()
        val ts = stamp()
        val txt = File(cacheDir, "IMEI_${model}_$ts.txt")
        txt.writeText("model=${Build.MODEL}\ndate=$ts\nimei1=$i1\nimei2=${i2 ?: ""}\n")
        NvramBackup.saveTextToBackupDir(txt.name, txt.absolutePath)
        log("IMEI محفوظ ہو گئے: ${NvramBackup.DIR}/${txt.name}")

        log("NVRAM بیک اپ لے رہا ہوں...")
        val (ok, info) = NvramBackup.backup(model, ts)
        log(if (ok) "NVRAM بیک اپ ✔ $info" else "بیک اپ ناکام: $info")
    }

    // ---------- Button 2: fix null IMEI from saved backup ----------
    private fun fixNull() {
        log("\n--- Null IMEI درست کرو ---")
        var (i1, i2) = ImeiManager.readAll(this)
        val bad1 = !ImeiManager.isValid(i1)
        val bad2 = i2 != null && !ImeiManager.isValid(i2)
        if (!bad1 && !bad2) { log("IMEI پہلے سے درست ہیں، کچھ کرنے کی ضرورت نہیں۔"); return }

        val saved1 = prefs.getString("imei1", null)
        val saved2 = prefs.getString("imei2", null)
        val backup = NvramBackup.latestBackup()

        if (backup == null && saved1 == null) {
            log("کوئی بیک اپ یا محفوظ IMEI نہیں ملا۔ تیسرا بٹن استعمال کریں اور فون کے پیچھے لکھا IMEI درج کریں۔")
            return
        }

        if (backup != null) {
            log("NVRAM بحال کر رہا ہوں: $backup")
            log(if (NvramBackup.restore(backup)) "NVRAM بحالی مکمل ✔" else "NVRAM بحالی ناکام ✘")
            val re = ImeiManager.readAll(this); i1 = re.first; i2 = re.second
        }

        if (!ImeiManager.isValid(i1) && saved1 != null) log(ImeiManager.safeWrite(this, 0, saved1))
        if (i2 != null || saved2 != null) {
            if (!ImeiManager.isValid(i2) && saved2 != null) log(ImeiManager.safeWrite(this, 1, saved2))
        }
        val (n1, n2) = ImeiManager.readAll(this)
        log("اب IMEI 1: ${describe(n1)}")
        log("اب IMEI 2: ${describe(n2)}")
        log("➡ موبائل ری اسٹارٹ کریں۔")
    }

    // ---------- Button 3: manual write from phone back sticker ----------
    private fun askManualImei() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0) }
        val e1 = EditText(this).apply { hint = "IMEI 1 (15 ہندسے)"; inputType = InputType.TYPE_CLASS_NUMBER }
        val e2 = EditText(this).apply { hint = "IMEI 2 (اختیاری)"; inputType = InputType.TYPE_CLASS_NUMBER }
        box.addView(e1); box.addView(e2)
        AlertDialog.Builder(this)
            .setTitle("فون کے پیچھے/ڈبے پر لکھا اصل IMEI")
            .setMessage("صرف اسی فون کا اپنا اصلی IMEI لکھیں۔ یہ صرف تب لکھا جائے گا جب موجودہ IMEI Null ہو۔")
            .setView(box)
            .setNegativeButton("منسوخ", null)
            .setPositiveButton("لکھو") { _, _ ->
                val a = e1.text.toString().trim()
                val b = e2.text.toString().trim()
                work {
                    log("\n--- IMEI دوبارہ لکھو ---")
                    if (a.isNotEmpty()) log(ImeiManager.safeWrite(this, 0, a))
                    if (b.isNotEmpty()) log(ImeiManager.safeWrite(this, 1, b))
                    val (n1, n2) = ImeiManager.readAll(this)
                    log("اب IMEI 1: ${describe(n1)}")
                    log("اب IMEI 2: ${describe(n2)}")
                    log("➡ موبائل ری اسٹارٹ کریں۔")
                }
            }.show()
    }

    private fun describe(imei: String?) = when {
        imei == null -> "نہیں ملا / سلاٹ خالی"
        ImeiManager.isValid(imei) -> "$imei ✔"
        else -> "$imei ✘ (Null/غلط)"
    }
}
