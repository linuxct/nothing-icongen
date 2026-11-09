package space.linuxct.nothing.icongen

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Animatable
import android.os.Build
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** File-backed artifacts shared by foreground builds and the serialized JIT worker. */
internal class PreparedIconCache(
    private val context: Context,
    root: File = File(context.cacheDir, "prepared_icons_v1"),
) {
    private val configuration = Configuration(context.resources.configuration)
    private val ownIdentity = PackageChangeMonitor.identity(context, Process.myUserHandle(), context.packageName)
    private val launcherIdentity = PackageChangeMonitor.identity(context, Process.myUserHandle(), "com.nothing.launcher")
    val lightPalette = ThemeColors.resolve(context, false)
    val darkPalette = ThemeColors.resolve(context, true)
    private val store = PreparedIconStore(root)
    private val environment = store.key(listOf("renderer-encoder-v1", Build.FINGERPRINT, configuration.toString(),
        ownIdentity.orEmpty(), launcherIdentity.orEmpty()).joinToString("\n"))
    private val protected = HashSet<File>()
    var reused = 0
        private set
    var rendered = 0
        private set
    var encoded = 0
        private set

    fun prepare(app: AppScanner.AppIcon, pipeline: IconPipeline,
                cancellation: BuildCoordinator.Cancellation): IconPackAssembler.PackEntry {
        cancellation.checkpoint()
        val group = store.group(app.profileSerial, app.componentName)
        val source = "$environment\n${app.sourceIdentity}"
        val lightKey = store.key("$source\nlight:${lightPalette.foreground}:${lightPalette.background}")
        val darkKey = store.key("$source\ndark:${darkPalette.foreground}:${darkPalette.background}")
        var light = store.find(group, lightKey)
        var dark = store.find(group, darkKey)
        if (light != null) protected.add(light.parentFile!!)
        if (dark != null) protected.add(dark.parentFile!!)
        if (light == null || dark == null) {
            val drawable = app.icon
            val reusable = drawable !is Animatable
            try {
                val glyphs = pipeline.glyph(drawable)
                rendered++
                try {
                    fun produce(key: String, palette: ThemeColors.Palette): File {
                        val bitmap = pipeline.compose(palette, glyphs)
                        try {
                            return store.publish(group, key, cancellation, reusable, validate = {
                                checkCurrent(app)
                            }) { file ->
                                FileOutputStream(file).use { output ->
                                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 90, output)) {
                                        throw IOException("Could not encode ${app.componentName}")
                                    }
                                }
                            }.also { encoded++; protected.add(it.parentFile!!) }
                        } finally { bitmap.recycle() }
                    }
                    if (light == null) light = produce(lightKey, lightPalette)
                    cancellation.checkpoint()
                    if (dark == null) dark = produce(darkKey, darkPalette)
                } finally { glyphs.recycle() }
            } finally { app.releaseIcon() }
        } else reused++
        cancellation.checkpoint()
        store.retain(group, setOf(lightKey, darkKey), protected)
        return IconPackAssembler.PackEntry(app.componentName, app.label, requireNotNull(light), requireNotNull(dark))
    }

    /** Validate the source again while publication is ordered against Stop/off. */
    private fun checkCurrent(app: AppScanner.AppIcon) {
        if (PackageChangeMonitor.identity(context, app.userHandle, app.sourcePackage) != app.sourceIdentity) {
            throw IOException("${app.sourcePackage} changed while its icons were being prepared")
        }
        checkEnvironment()
    }

    fun checkEnvironment() {
        if (configuration != context.resources.configuration ||
            lightPalette != ThemeColors.resolve(context, false) || darkPalette != ThemeColors.resolve(context, true) ||
            ownIdentity != PackageChangeMonitor.identity(context, Process.myUserHandle(), context.packageName) ||
            launcherIdentity != PackageChangeMonitor.identity(context, Process.myUserHandle(), "com.nothing.launcher")) {
            throw IOException("Rendering configuration changed while preparing the icon pack")
        }
    }

    fun prune(inventory: AppScanner.Inventory) {
        val live = inventory.apps.mapTo(HashSet()) { store.group(it.profileSerial, it.componentName) }
        store.prune(live, inventory.completeProfiles)
    }

    /** The assembler has copied its current inputs before a full build releases these pins. */
    fun trim(release: Boolean = false) {
        if (release) protected.clear()
        store.trim(protected)
    }
}

/** Immutable, checksum-validated PNG entries; a directory rename publishes pixels and metadata together. */
internal class PreparedIconStore(private val root: File, private val maxBytes: Long = 128L * 1024 * 1024) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val buffer = ByteArray(8192)

    fun group(profile: Long, component: String): String = "${profile}_${key(component)}"
    fun key(value: String): String = hex(digest.apply { reset() }.digest(value.toByteArray(Charsets.UTF_8)))

    fun find(group: String, key: String): File? {
        val directory = directory(group, key)
        val image = File(directory, "image.png")
        val checksum = File(directory, "sha256")
        val valid = try {
            image.isFile && checksum.isFile && image.length() > 0 && checksum.readText() == hash(image)
        } catch (_: IOException) { false }
        if (!valid) {
            directory.deleteRecursively()
            return null
        }
        val now = System.currentTimeMillis()
        if (now - directory.lastModified() >= 60_000) directory.setLastModified(now)
        return image
    }

    fun publish(group: String, key: String, cancellation: BuildCoordinator.Cancellation,
                reusable: Boolean = true, validate: () -> Unit = {}, write: (File) -> Unit): File {
        cancellation.checkpoint()
        find(group, key)?.let { return it }
        val target = directory(group, key)
        val parent = target.parentFile!!
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Could not create icon cache")
        val pending = File(parent, ".pending-${UUID.randomUUID()}")
        if (!pending.mkdir()) throw IOException("Could not stage cached icon")
        try {
            val file = File(pending, "image.png")
            write(file)
            cancellation.checkpoint()
            if (!file.isFile || file.length() == 0L) throw IOException("Icon cache producer wrote no PNG")
            File(pending, "sha256").writeText(if (reusable) hash(file) else "transient")
            cancellation.commit {
                validate()
                if (target.exists()) {
                    if (find(group, key) != null) return@commit
                    if (target.exists() && !target.deleteRecursively()) throw IOException("Could not repair icon cache")
                }
                if (!pending.renameTo(target)) throw IOException("Could not publish cached icon")
            }
            return File(target, "image.png")
        } finally { pending.deleteRecursively() }
    }

    fun retain(group: String, keys: Set<String>, protected: Set<File>) {
        val parent = File(root, group)
        parent.listFiles()?.forEach { child ->
            if (child.isDirectory && child.name !in keys && child !in protected) child.deleteRecursively()
        }
    }

    fun prune(liveGroups: Set<String>, completeProfiles: Set<Long>) {
        root.listFiles()?.forEach { group ->
            val profile = group.name.substringBefore('_').toLongOrNull()
            if (profile in completeProfiles && group.name !in liveGroups) group.deleteRecursively()
        }
    }

    fun trim(protected: Set<File> = emptySet()) {
        val entries = root.listFiles()?.flatMap { group -> group.listFiles()?.filter { it.isDirectory }.orEmpty() }.orEmpty()
        val sizes = entries.associateWith { entry -> entry.walkTopDown().filter { it.isFile }.sumOf { maxOf(4096L, it.length()) } + 4096 }
        var total = sizes.values.sum()
        for (entry in entries.sortedBy { it.lastModified() }) {
            if (entry.name.startsWith(".pending-") && entry !in protected) {
                if (entry.deleteRecursively()) total -= sizes.getValue(entry)
            } else if (total > maxBytes && entry !in protected && entry.deleteRecursively()) {
                total -= sizes.getValue(entry)
            }
        }
    }

    private fun directory(group: String, key: String): File {
        require(GROUP.matches(group) && KEY.matches(key)) { "Invalid icon cache identity" }
        return File(File(root, group), key)
    }

    private fun hash(file: File): String {
        digest.reset()
        file.inputStream().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 255
            chars[index * 2] = HEX[value ushr 4]
            chars[index * 2 + 1] = HEX[value and 15]
        }
        return String(chars)
    }

    companion object {
        private const val HEX = "0123456789abcdef"
        private val GROUP = Regex("-?[0-9]+_[0-9a-f]{64}")
        private val KEY = Regex("[0-9a-f]{64}")
    }
}
