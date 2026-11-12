package com.rewardadguard.app.collector

import android.content.Context
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Zips the newest `collector_runs/run_*` directory into the existing
 * FileProvider export area, so a run record can leave the phone through the
 * normal share sheet instead of `adb run-as`.
 *
 * Run records live in the private files directory (screenshots included), so
 * only the finished zip is placed in `cache/exports`, which `file_paths.xml`
 * already exposes. The zip keeps the run directory name as its top folder, so
 * the private test-log repository can unpack it as one run.
 */
object CollectorRunExporter {

    data class ExportResult(val file: File, val uri: android.net.Uri)

    fun exportLatest(context: Context): ExportResult? =
        export(context, latestRunDir(context))

    fun latestRunDir(context: Context): File? =
        File(context.filesDir, "collector_runs")
            .listFiles { file -> file.isDirectory && file.name.startsWith("run_") }
            ?.maxByOrNull { it.name }

    fun export(context: Context, runDir: File?): ExportResult? {
        if (runDir == null || !runDir.isDirectory) return null
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(exportDir, "${runDir.name}.zip")
        runCatching {
            ZipOutputStream(file.outputStream().buffered()).use { zip ->
                runDir.walkTopDown().filter { it.isFile }.forEach { source ->
                    val relative = source.relativeTo(runDir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry("${runDir.name}/$relative"))
                    source.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }.onFailure {
            runCatching { file.delete() }
            return null
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return ExportResult(file, uri)
    }
}
