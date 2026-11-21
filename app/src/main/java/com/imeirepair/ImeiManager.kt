package com.imeirepair

import android.content.Context
import android.telephony.TelephonyManager

/**
 * IMEI read / write helper.
 * Read order : TelephonyManager -> AT command (MediaTek) -> `service call iphonesubinfo`.
 * Write      : MediaTek AT+EGMR only (needs root). Other chipsets are NOT supported.
 */
object ImeiManager {

    private const val AT_DEV = "/dev/radio/pttycmd1"
    private const val AT_OUT = "/data/local/tmp/at.out"

    // ---------- validation ----------
    fun luhn(s: String): Boolean {
        var sum = 0
        for ((i, c) in s.reversed().withIndex()) {
            var d = c - '0'
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return sum % 10 == 0
    }

    fun isValid(imei: String?): Boolean =
        imei != null && imei.length == 15 && imei.all { it.isDigit() } &&
            !imei.all { it == '0' } && luhn(imei)

    // ---------- reading ----------
    fun readAll(ctx: Context): Pair<String?, String?> = Pair(read(ctx, 0), read(ctx, 1))

    fun read(ctx: Context, slot: Int): String? {
        val candidates = listOf(
            { readTelephony(ctx, slot) },
            { readAt(slot) },
            { readServiceCall(slot) }
        )
        var fallback: String? = null
        for (c in candidates) {
            val v = try { c() } catch (e: Exception) { null }
            if (v.isNullOrBlank()) continue
            if (isValid(v)) return v
            if (fallback == null) fallback = v   // e.g. 000000000000000 (null IMEI)
        }
        return fallback
    }

    private fun readTelephony(ctx: Context, slot: Int): String? = try {
        ctx.getSystemService(TelephonyManager::class.java).getImei(slot)
    } catch (e: SecurityException) { null }

    private fun readAt(slot: Int): String? {
        val type = if (slot == 0) 7 else 10
        val r = at("AT+EGMR=0,$type")
        return Regex("\\+EGMR: ?\"(\\d{15})\"").find(r)?.groupValues?.get(1)
    }

    private fun readServiceCall(slot: Int): String? {
        for (code in 1..6) {
            for (args in listOf("i32 $slot s16 com.android.shell", "i32 $slot")) {
                val out = RootShell.run("service call iphonesubinfo $code $args", 10).out
                val quoted = Regex("'([^']*)'").findAll(out).joinToString("") { it.groupValues[1] }
                val m = Regex("\\d{15}").find(quoted.replace(".", ""))?.value
                if (m != null && isValid(m)) return m
            }
        }
        return null
    }

    // ---------- AT channel (MediaTek) ----------
    private fun at(cmd: String): String {
        if (!RootShell.run("[ -e $AT_DEV ] && echo yes", 10).out.contains("yes")) return ""
        val script = listOf(
            "rm -f $AT_OUT",
            "(timeout 4 cat $AT_DEV > $AT_OUT 2>/dev/null &)",
            "sleep 1",
            "printf '%s\\r\\n' '$cmd' > $AT_DEV",
            "sleep 2",
            "cat $AT_OUT"
        ).joinToString("\n")
        return RootShell.run(script, 20).out
    }

    // ---------- writing ----------
    /**
     * SAFETY: only repairs an invalid/null IMEI. If the slot already holds a valid IMEI
     * that is different from [imei], the write is refused.
     */
    fun safeWrite(ctx: Context, slot: Int, imei: String): String {
        if (!isValid(imei)) return "IMEI ${slot + 1}: نمبر غلط ہے (15 ہندسے اور Luhn چیک پاس ہونا ضروری ہے)"
        val current = read(ctx, slot)
        if (isValid(current) && current != imei)
            return "IMEI ${slot + 1}: موجودہ IMEI درست ہے، تبدیل کرنے سے انکار۔ یہ ٹول صرف Null IMEI ٹھیک کرتا ہے۔"
        if (current == imei) return "IMEI ${slot + 1}: پہلے سے درست ہے ($imei)"
        val type = if (slot == 0) 7 else 10
        val r = at("AT+EGMR=1,$type,\"$imei\"")
        return if (r.contains("OK")) "IMEI ${slot + 1}: لکھ دیا گیا ✔ (موبائل ری اسٹارٹ کریں)"
        else "IMEI ${slot + 1}: لکھنا ناکام۔ جواب: ${r.trim().ifEmpty { "کوئی جواب نہیں (AT چینل نہیں ملا؟)" }}"
    }
}
