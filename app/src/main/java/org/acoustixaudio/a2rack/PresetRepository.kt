package org.acoustixaudio.a2rack

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class AssetKind(val extension: String) { NAM("nam"), IR("wav") }

@JsonClass(generateAdapter = true)
data class AssetRef(val id: String, val name: String)

@JsonClass(generateAdapter = true)
data class RackPreset(
    val schemaVersion: Int = 1,
    val inputGain: Float = 0.5f,
    val outputGain: Float = 0.5f,
    val isAmpBypassed: Boolean = false,
    val isCabBypassed: Boolean = false,
    val isA2Full: Boolean = false,
    val nam: AssetRef? = null,
    val ir: AssetRef? = null
) {
    fun validate(): RackPreset {
        require(schemaVersion == 1) { "Unsupported preset version: $schemaVersion." }
        require(inputGain.isFinite() && inputGain in 0f..1f &&
            outputGain.isFinite() && outputGain in 0f..1f) { "Preset gains must be between 0 and 1." }
        listOfNotNull(nam, ir).forEach {
            require(it.id.matches(Regex("[a-f0-9]{64}"))) { "Invalid asset ID." }
            require(it.name.isNotBlank() && it.name.length <= 128 &&
                it.name.none { c -> c.isISOControl() || c == '/' || c == '\\' }) { "Invalid asset filename." }
        }
        return this
    }
}

@JsonClass(generateAdapter = true)
internal data class LegacyPreset(
    val inputGain: Float = 0.5f,
    val outputGain: Float = 0.5f,
    val isAmpBypassed: Boolean = false,
    val isCabBypassed: Boolean = false,
    val isA2Full: Boolean = false,
    val namFilePath: String? = null,
    val wavFilePath: String? = null
)

/** File-only repository: callers serialize operations and run them off the UI thread. */
class PresetRepository(private val root: File, private val legacyCache: File? = null) {
    companion object {
        private const val MAX_JSON = 256L * 1024
        private const val MAX_ASSET = 128L * 1024 * 1024
        private const val MAX_BUNDLE = 2 * MAX_ASSET + MAX_JSON
    }

    private val moshi = Moshi.Builder().build()
    private val adapter = moshi.adapter(RackPreset::class.java).indent("  ")
    private val legacyAdapter = moshi.adapter(LegacyPreset::class.java)
    private val assets get() = File(root, "assets")
    private val session get() = File(root, "last-session.json")

    private fun prepare() { check(assets.isDirectory || assets.mkdirs()) { "Cannot create model library." } }

    fun assetFile(ref: AssetRef, kind: AssetKind): File {
        RackPreset(nam = ref).validate()
        return File(assets, "${ref.id}.${kind.extension}")
    }

    fun resolve(ref: AssetRef, kind: AssetKind): File? =
        assetFile(ref, kind).takeIf { it.isFile && it.length() in 1..MAX_ASSET && digest(it) == ref.id }

    fun importAsset(input: InputStream, displayName: String, kind: AssetKind): AssetRef = input.use { source ->
        prepare()
        val staging = File.createTempFile("asset-", ".tmp", root)
        try {
            staging.outputStream().use { copyBounded(source, it, MAX_ASSET) }
            require(staging.length() > 0) { "The selected file is empty." }
            val ref = AssetRef(digest(staging), safeName(displayName, kind))
            if (resolve(ref, kind) == null) move(staging, assetFile(ref, kind))
            ref
        } finally { staging.delete() }
    }

    fun saveSession(preset: RackPreset) {
        prepare()
        val staging = File.createTempFile("session-", ".tmp", root)
        try {
            staging.writeText(encode(preset))
            move(staging, session)
        } finally { staging.delete() }
    }

    fun readSession(): RackPreset? =
        if (session.isFile) session.inputStream().use { decode(readBounded(it, MAX_JSON).toString(Charsets.UTF_8)) }
        else null

    fun writeJson(preset: RackPreset, output: OutputStream) {
        output.use { it.write(encode(preset).toByteArray(Charsets.UTF_8)) }
    }

    fun writeBundle(preset: RackPreset, output: OutputStream) {
        preset.validate()
        val entries = listOfNotNull(
            preset.nam?.let { ref -> "assets/amp.nam" to
                (resolve(ref, AssetKind.NAM) ?: error("Missing amp: ${ref.name}. Replace it before exporting a bundle.")) },
            preset.ir?.let { ref -> "assets/cab.wav" to
                (resolve(ref, AssetKind.IR) ?: error("Missing cabinet: ${ref.name}. Replace it before exporting a bundle.")) }
        )
        // Check the whole bundle before writing anything to the destination.
        entries.forEach { (_, file) ->
            require(file.length() in 1..MAX_ASSET) { "Asset is empty or too large." }
        }
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("preset.json"))
            zip.write(encode(preset).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            entries.forEach { (name, file) ->
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { copyBounded(it, zip, MAX_ASSET) }
                zip.closeEntry()
            }
        }
    }

    fun readPreset(input: InputStream): RackPreset {
        prepare()
        PushbackInputStream(input, 4).use { source ->
            val signature = ByteArray(4)
            var count = 0
            while (count < 4) {
                val read = source.read(signature, count, 4 - count)
                if (read < 0) break
                count += read
            }
            source.unread(signature, 0, count)
            return if (count == 4 && signature[0] == 0x50.toByte() && signature[1] == 0x4b.toByte())
                readBundle(source)
            else decode(readBounded(source, MAX_JSON).toString(Charsets.UTF_8))
        }
    }

    private fun readBundle(input: InputStream): RackPreset {
        val staging = Files.createTempDirectory(root.toPath(), "bundle-").toFile()
        try {
            val names = mutableSetOf<String>()
            var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && entry.name in setOf("preset.json", "assets/amp.nam", "assets/cab.wav") &&
                        names.add(entry.name)) { "Unexpected or duplicate preset bundle entry." }
                    val destination = File(staging, entry.name.substringAfterLast('/'))
                    destination.outputStream().use { target ->
                        total += copyBounded(zip, target, if (entry.name == "preset.json") MAX_JSON else MAX_ASSET)
                    }
                    require(total <= MAX_BUNDLE) { "Preset bundle is too large." }
                    zip.closeEntry()
                }
            }
            val manifest = File(staging, "preset.json")
            require(manifest.isFile) { "The bundle has no preset.json." }
            // Bundles require the current schema; never interpret legacy paths.
            val preset = decodeCurrent(manifest.readText())
            val bundled = listOf(
                Triple(preset.nam, AssetKind.NAM, File(staging, "amp.nam")),
                Triple(preset.ir, AssetKind.IR, File(staging, "cab.wav"))
            )
            bundled.forEach { (ref, _, file) ->
                require((ref != null) == file.isFile) { "Bundle asset does not match its preset." }
                if (ref != null) require(file.length() > 0 && digest(file) == ref.id) { "Bundle asset checksum mismatch." }
            }
            // Validate all assets before publishing any into persistent storage.
            bundled.forEach { (ref, kind, file) ->
                if (ref != null) {
                    val destination = assetFile(ref, kind)
                    if (resolve(ref, kind) == null) move(file, destination)
                }
            }
            return preset
        } finally { staging.deleteRecursively() }
    }

    private fun encode(preset: RackPreset) = adapter.toJson(preset.validate())

    private fun decodeCurrent(json: String): RackPreset {
        val fields = moshi.adapter(Map::class.java).fromJson(json) ?: error("Empty preset.")
        require(fields.containsKey("schemaVersion")) { "The bundle needs a versioned preset." }
        return (adapter.fromJson(json) ?: error("Empty preset.")).validate()
    }

    private fun decode(json: String): RackPreset {
        val fields = moshi.adapter(Map::class.java).fromJson(json) ?: error("Empty preset.")
        if (fields.containsKey("schemaVersion")) return decodeCurrent(json)
        require("nam" !in fields && "ir" !in fields) { "Preset schemaVersion is missing." }
        // A legacy preset must have at least one recognized setting.
        require(fields.keys.any { it in setOf("inputGain", "outputGain", "namFilePath", "wavFilePath",
            "isAmpBypassed", "isCabBypassed", "isA2Full") }) { "Unrecognized preset." }
        val old = legacyAdapter.fromJson(json) ?: error("Empty preset.")
        // Validate settings before migrating any assets.
        val preset = RackPreset(inputGain = old.inputGain, outputGain = old.outputGain,
            isAmpBypassed = old.isAmpBypassed, isCabBypassed = old.isCabBypassed,
            isA2Full = old.isA2Full).validate()
        return preset.copy(nam = migrate(old.namFilePath, AssetKind.NAM),
            ir = migrate(old.wavFilePath, AssetKind.IR)).validate()
    }

    private fun migrate(path: String?, kind: AssetKind): AssetRef? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        // Never allow an imported JSON path to read arbitrary app-private files.
        val allowed = legacyCache != null && file.canonicalFile.parentFile == legacyCache.canonicalFile &&
            file.extension.equals(kind.extension, ignoreCase = true) &&
            listOf("a2rack_", "nam_model", "cab_ir").any { file.name.startsWith(it) }
        if (allowed && file.isFile) return file.inputStream().use { importAsset(it, file.name, kind) }
        // Preserve the missing reference so the user can choose a replacement.
        return AssetRef(hash(path.toByteArray()), safeName(file.name, kind))
    }

    private fun safeName(name: String, kind: AssetKind): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .filterNot { it.isISOControl() }.take(128).ifBlank { "model.${kind.extension}" }

    private fun move(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun readBounded(input: InputStream, max: Long): ByteArray =
        java.io.ByteArrayOutputStream().also { copyBounded(input, it, max) }.toByteArray()

    private fun copyBounded(input: InputStream, output: OutputStream, max: Long): Long {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= max) { "Selected file exceeds the supported size limit." }
            output.write(buffer, 0, count)
        }
        return total
    }
}
