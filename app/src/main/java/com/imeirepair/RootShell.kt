package com.imeirepair

import java.util.concurrent.TimeUnit

object RootShell {

    data class Result(val code: Int, val out: String, val err: String) {
        val ok get() = code == 0
    }

    /** Runs a command (or multi-line script) as root via `su -c`. */
    fun run(cmd: String, timeoutSec: Long = 120): Result {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).start()
            val out = StringBuilder()
            val err = StringBuilder()
            val t1 = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
            val t2 = Thread { p.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
            t1.start(); t2.start()
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return Result(-1, out.toString(), "timeout")
            }
            t1.join(2000); t2.join(2000)
            Result(p.exitValue(), out.toString(), err.toString())
        } catch (e: Exception) {
            Result(-1, "", e.message ?: "su not available")
        }
    }

    fun isRootAvailable(): Boolean = run("id", 15).out.contains("uid=0")
}
