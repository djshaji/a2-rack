package org.acoustixaudio.a2rack

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PresetRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun repository(name: String = "library") = PresetRepository(temporary.newFolder(name))
    private fun asset(repo: PresetRepository, text: String = "amp bytes", name: String = "My Amp.nam") =
        repo.importAsset(text.byteInputStream(), name, AssetKind.NAM)
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
            }
        }.toByteArray()

    @Test fun assetsArePersistentDeduplicatedAndKeepDisplayNames() {
        val root = temporary.newFolder()
        val repo = PresetRepository(root)
        val first = asset(repo)
        val second = asset(repo, name = "Another name.nam")
        assertEquals(first.id, second.id)
        assertEquals("My Amp.nam", first.name)
        assertEquals(1, File(root, "assets").listFiles()!!.size)
        assertEquals("amp bytes", PresetRepository(root).resolve(first, AssetKind.NAM)!!.readText())
    }

    @Test fun reimportRepairsAnAssetWhoseStoredBytesWereDamaged() {
        val repo = repository()
        val ref = asset(repo)
        repo.assetFile(ref, AssetKind.NAM).writeText("damaged")
        assertNull(repo.resolve(ref, AssetKind.NAM))
        assertEquals(ref, asset(repo))
        assertEquals("amp bytes", repo.resolve(ref, AssetKind.NAM)!!.readText())
    }

    @Test fun currentReferencesWithoutVersionAreNotTreatedAsLegacyPaths() {
        val repo = repository()
        val json = """{"inputGain":0.5,"nam":{"id":"${"a".repeat(64)}","name":"Amp.nam"}}"""
        assertThrows(IllegalArgumentException::class.java) { repo.readPreset(json.byteInputStream()) }
    }

    @Test fun sessionSurvivesRepositoryRecreationAndInvalidSavePreservesIt() {
        val root = temporary.newFolder()
        val repo = PresetRepository(root)
        val preset = RackPreset(inputGain = 0.25f, isCabBypassed = true, nam = asset(repo))
        repo.saveSession(preset)
        assertThrows(IllegalArgumentException::class.java) { repo.saveSession(preset.copy(inputGain = 2f)) }
        assertEquals(preset, PresetRepository(root).readSession())
        assertTrue(root.listFiles()!!.none { it.extension == "tmp" })
    }

    @Test fun portableBundleRoundTripsIntoAnEmptyLibrary() {
        val source = repository("source")
        val amp = asset(source)
        val ir = source.importAsset("wav bytes".byteInputStream(), "Cab.wav", AssetKind.IR)
        val preset = RackPreset(inputGain = 0.7f, outputGain = 0.3f, isA2Full = true,
            isAmpBypassed = true, nam = amp, ir = ir)
        val output = ByteArrayOutputStream()
        source.writeBundle(preset, output)
        val destination = repository("destination")
        val restored = destination.readPreset(ByteArrayInputStream(output.toByteArray()))
        assertEquals(preset, restored)
        assertEquals("amp bytes", destination.resolve(restored.nam!!, AssetKind.NAM)!!.readText())
        assertEquals("wav bytes", destination.resolve(restored.ir!!, AssetKind.IR)!!.readText())
    }

    @Test fun settingsOnlyPresetRetainsMissingReferenceForReplacement() {
        val source = repository("source")
        val preset = RackPreset(nam = asset(source))
        val output = ByteArrayOutputStream()
        source.writeJson(preset, output)
        val destination = repository("destination")
        val restored = destination.readPreset(ByteArrayInputStream(output.toByteArray()))
        assertEquals(preset, restored)
        assertNull(destination.resolve(restored.nam!!, AssetKind.NAM))
        assertThrows(IllegalStateException::class.java) {
            destination.writeBundle(restored, ByteArrayOutputStream())
        }
    }

    @Test fun unsupportedVersionsInvalidGainsAndUnsafeIdsAreRejected() {
        val repo = repository()
        listOf(
            """{"schemaVersion":2}""",
            """{"schemaVersion":1,"inputGain":-1}""",
            """{"schemaVersion":1,"outputGain":2}""",
            """{"schemaVersion":1,"nam":{"id":"../secret","name":"amp.nam"}}""",
            """{"schemaVersion":1,"nam":{"id":"${"a".repeat(64)}","name":"../amp.nam"}}""",
            """{}"""
        ).forEach { json ->
            assertThrows(Exception::class.java) { repo.readPreset(json.byteInputStream()) }
        }
    }

    @Test fun tamperedBundleIsRejectedBeforePublishingAssets() {
        val source = repository("source")
        val preset = RackPreset(nam = asset(source))
        val manifest = ByteArrayOutputStream().also { source.writeJson(preset, it) }.toByteArray()
        val root = temporary.newFolder("destination")
        val repo = PresetRepository(root)
        val bundle = zip("preset.json" to manifest, "assets/amp.nam" to "tampered".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { repo.readPreset(ByteArrayInputStream(bundle)) }
        assertTrue(File(root, "assets").listFiles()!!.isEmpty())
        assertTrue(root.listFiles()!!.none { it.name.startsWith("bundle-") })
    }

    @Test fun archivePathsAndMissingPayloadsAreRejected() {
        val repo = repository()
        val traversal = zip("../escaped.nam" to byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { repo.readPreset(ByteArrayInputStream(traversal)) }
        assertFalse(File(temporary.root, "escaped.nam").exists())
        val manifest = """{"schemaVersion":1,"nam":{"id":"${"a".repeat(64)}","name":"Amp.nam"}}"""
        assertThrows(IllegalArgumentException::class.java) {
            repo.readPreset(ByteArrayInputStream(zip("preset.json" to manifest.toByteArray())))
        }
    }

    @Test fun legacyCacheAssetsMigrateButOtherPrivatePathsAreNotRead() {
        val cache = temporary.newFolder("cache")
        val old = File(cache, "a2rack_old.nam").apply { writeText("legacy amp") }
        val secret = temporary.newFile("a2rack_secret.nam").apply { writeText("private data") }
        val root = temporary.newFolder("library")
        val repo = PresetRepository(root, cache)
        val legacy = """{"inputGain":0.2,"namFilePath":"${old.absolutePath}","wavFilePath":null}"""
        val restored = repo.readPreset(legacy.byteInputStream())
        assertEquals("legacy amp", repo.resolve(restored.nam!!, AssetKind.NAM)!!.readText())
        old.delete()
        assertNotNull(repo.resolve(restored.nam, AssetKind.NAM))
        val unsafe = """{"namFilePath":"${secret.absolutePath}"}"""
        val missing = repo.readPreset(unsafe.byteInputStream())
        assertNull(repo.resolve(missing.nam!!, AssetKind.NAM))
        assertEquals(1, File(root, "assets").listFiles()!!.size)
    }

    @Test fun interruptedAndEmptyAssetImportsLeaveNoTemporaryFiles() {
        val root = temporary.newFolder()
        val repo = PresetRepository(root)
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("Provider disconnected")
        }
        assertThrows(IOException::class.java) { repo.importAsset(broken, "amp.nam", AssetKind.NAM) }
        assertThrows(IllegalArgumentException::class.java) {
            repo.importAsset(ByteArrayInputStream(byteArrayOf()), "amp.nam", AssetKind.NAM)
        }
        assertTrue(root.listFiles()!!.none { it.extension == "tmp" })
        assertTrue(File(root, "assets").listFiles()!!.isEmpty())
    }

    @Test fun oversizedJsonIsRejected() {
        val repo = repository()
        assertThrows(IllegalArgumentException::class.java) {
            repo.readPreset(" ".repeat(256 * 1024 + 1).byteInputStream())
        }
    }
}
