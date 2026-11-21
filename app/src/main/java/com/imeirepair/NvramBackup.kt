package com.imeirepair

/**
 * NVRAM backup / restore for MediaTek devices (root required).
 * Backup file: /sdcard/IMEI_Repair/NVRAM_<Model>_<yyyy-MM-dd_HH-mm>.tar.gz
 */
object NvramBackup {

    const val DIR = "/sdcard/IMEI_Repair"

    private const val FIND_BY =
        "if [ -d /dev/block/by-name ]; then BY=/dev/block/by-name; " +
        "else BY=\$(ls -d /dev/block/platform/*/by-name 2>/dev/null | head -n 1); fi"

    private val DIRS = listOf(
        "/mnt/vendor/nvdata", "/mnt/vendor/nvcfg", "/mnt/vendor/protect_f", "/mnt/vendor/protect_s",
        "/data/nvram", "/nvdata", "/protect_f", "/protect_s"
    ).joinToString(" ")

    fun backup(model: String, stamp: String): Pair<Boolean, String> {
        val dest = "$DIR/NVRAM_${model}_$stamp.tar.gz"
        val script = listOf(
            "T=/data/local/tmp/nvbk",
            "rm -rf \$T; mkdir -p \$T/parts \$T/dirs $DIR",
            FIND_BY,
            "for p in nvram nvdata protect1 protect2; do [ -e \"\$BY/\$p\" ] && dd if=\"\$BY/\$p\" of=\"\$T/parts/\$p.img\" bs=1048576 2>/dev/null; done",
            "for d in $DIRS; do [ -d \"\$d\" ] && tar -czf \"\$T/dirs/\$(echo \$d | tr / _).tgz\" -C / \"\${d#/}\" 2>/dev/null; done",
            "C=\$(find \$T -type f | wc -l)",
            "if [ \"\$C\" -gt 0 ]; then tar -czf \"$dest\" -C \$T . && chmod 666 \"$dest\" && echo BACKUP_OK:\$C; else echo BACKUP_EMPTY; fi",
            "rm -rf \$T"
        ).joinToString("\n")
        val r = RootShell.run(script, 300)
        return if (r.out.contains("BACKUP_OK")) Pair(true, dest)
        else Pair(false, "NVRAM پارٹیشن/فولڈر نہیں ملے (یہ فون MediaTek نہیں ہو سکتا)۔ ${r.err.trim()}")
    }

    fun latestBackup(): String? {
        val r = RootShell.run("ls -t $DIR/NVRAM_*.tar.gz 2>/dev/null | head -n 1", 15)
        return r.out.trim().ifEmpty { null }
    }

    /** Restores the nvram partition and nvdata/protect folders from [src]. */
    fun restore(src: String): Boolean {
        val script = listOf(
            "T=/data/local/tmp/nvrs",
            "rm -rf \$T; mkdir -p \$T",
            "tar -xzf \"$src\" -C \$T || exit 1",
            FIND_BY,
            "if [ -f \$T/parts/nvram.img ] && [ -n \"\$BY\" ]; then dd if=\$T/parts/nvram.img of=\$BY/nvram bs=1048576 2>/dev/null; fi",
            "for f in \$T/dirs/*.tgz; do [ -f \"\$f\" ] && tar -xzf \"\$f\" -C /; done",
            "sync",
            "rm -rf \$T",
            "echo RESTORE_DONE"
        ).joinToString("\n")
        return RootShell.run(script, 300).out.contains("RESTORE_DONE")
    }

    fun saveTextToBackupDir(fileName: String, localPath: String) {
        RootShell.run("mkdir -p $DIR; cp \"$localPath\" \"$DIR/$fileName\"; chmod 666 \"$DIR/$fileName\"", 20)
    }
}
