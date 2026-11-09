package space.linuxct.nothing.icongen

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.security.MessageDigest

/** Content-addressed native AAPT2 outputs. The caller serializes compilation, linking and eviction. */
internal class AaptResourceCache(
    private val root: File,
    private val compileFlags: List<String> = emptyList(),
) {
    data class Result(val files: List<File>, val hits: Int, val misses: Int)
    private data class Source(val file: File, val outputName: String, val entry: File)

    fun compile(
        resourceDir: File,
        aapt2: File,
        workDir: File,
        cancellation: BuildCoordinator.Cancellation,
        command: (List<String>) -> Unit,
    ): Result {
        val started = SystemClock.elapsedRealtime()
        root.mkdirs()
        // Hash the executable itself, not a version label which may survive a tool replacement.
        val compiler = digest(aapt2)
        val sources = resourceDir.walkTopDown().filter { it.isFile }.sortedBy {
            it.relativeTo(resourceDir).invariantSeparatorsPath
        }.map { file ->
            cancellation.checkpoint()
            val path = file.relativeTo(resourceDir).invariantSeparatorsPath
            require(path.count { it == '/' } == 1) { "Unsupported resource path: $path" }
            val key = digest("native-flat-v1\n$compiler\ncompile\n${compileFlags.joinToString("\u0000")}\n$path\n${digest(file)}".toByteArray())
            val directory = path.substringBefore('/')
            val output = if (directory.substringBefore('-') == "values") {
                "${directory}_${file.nameWithoutExtension}.arsc.flat"
            } else "${directory}_${file.name}.flat"
            Source(file, output, File(root, key))
        }.toList()
        val misses = sources.filterNot { valid(it.entry) }
        // Bulk explicit inputs produce genuine .flat files without a compile ZIP. Batching also
        // bounds argv on Android; a cold pack needs only a handful of launches rather than N.
        val batches = mutableListOf<MutableList<Source>>()
        var bytes = 0
        for (source in misses) {
            val size = source.file.absolutePath.toByteArray().size + 1
            if (batches.isEmpty() || bytes + size > 48 * 1024) {
                batches.add(mutableListOf())
                bytes = 0
            }
            batches.last().add(source)
            bytes += size
        }
        for ((index, batch) in batches.withIndex()) {
            cancellation.checkpoint()
            val output = File(workDir, "flat_$index").apply { check(mkdirs()) }
            command(listOf(aapt2.absolutePath, "compile") + compileFlags +
                listOf("-o", output.absolutePath) + batch.map { it.file.absolutePath })
            for (source in batch) {
                cancellation.checkpoint()
                val flat = File(output, source.outputName)
                check(flat.isFile && flat.length() > 0) { "AAPT2 did not produce ${source.outputName}" }
                val staged = File(root, ".${source.entry.name}_${System.nanoTime()}").apply { check(mkdirs()) }
                try {
                    check(flat.renameTo(File(staged, "resource.flat"))) { "Cannot stage compiled resource" }
                    File(staged, "sha256").writeText(digest(File(staged, "resource.flat")))
                    source.entry.deleteRecursively()
                    check(staged.renameTo(source.entry)) { "Cannot publish compiled resource ${source.entry.name}" }
                } finally { staged.deleteRecursively() }
            }
        }
        val files = sources.map {
            it.entry.setLastModified(System.currentTimeMillis())
            File(it.entry, "resource.flat")
        }
        Log.d("IconPackAssembler", "AAPT2 compile: hits=${sources.size - misses.size} misses=${misses.size} ms=${SystemClock.elapsedRealtime() - started}")
        return Result(files, sources.size - misses.size, misses.size)
    }

    /** Called after link/sign: the complete active inventory is protected even if over the limit. */
    fun evict(active: List<File>, maxBytes: Long = 256L * 1024 * 1024, maxEntries: Int = 12_000) {
        val protected = active.mapTo(HashSet()) { it.parentFile!!.name }
        val entries = root.listFiles()?.toList().orEmpty()
        var bytes = entries.sumOf { entry -> entry.listFiles()?.sumOf { it.length() } ?: 0L }
        var count = entries.size
        for (entry in entries.sortedBy { it.lastModified() }) {
            if (entry.name in protected) continue
            if (!entry.name.startsWith('.') && bytes <= maxBytes && count <= maxEntries) continue
            val size = entry.listFiles()?.sumOf { it.length() } ?: 0L
            if (entry.deleteRecursively()) { bytes -= size; count-- }
        }
    }

    private fun valid(entry: File): Boolean = try {
        val flat = File(entry, "resource.flat")
        flat.isFile && flat.length() > 0 && File(entry, "sha256").readText() == digest(flat)
    } catch (_: java.io.IOException) { false }

    companion object {
        private class HashState {
            val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
        }
        private val hashes = object : ThreadLocal<HashState>() {
            override fun initialValue() = HashState()
        }
        internal fun digest(file: File): String {
            val state = hashes.get()!!
            val digest = state.digest.apply { reset() }
            file.inputStream().use { input ->
                val buffer = state.buffer
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return hex(digest.digest())
        }
        internal fun digest(bytes: ByteArray): String = hex(hashes.get()!!.digest.apply { reset() }.digest(bytes))
        private fun hex(bytes: ByteArray): String {
            val alphabet = "0123456789abcdef"
            return CharArray(bytes.size * 2) { index ->
                val value = bytes[index / 2].toInt() and 255
                alphabet[if (index % 2 == 0) value ushr 4 else value and 15]
            }.concatToString()
        }
    }
}
