package space.linuxct.nothing.icongen

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class WirelessAdbTransportTest {
    private val staging = AdbInstallStaging("0123456789abcdef0123456789abcdef")

    private data class Received(val bytes: ByteArray, val chunks: List<Int>)

    /** A second endpoint consumes and checks real sync frames before acknowledging the upload. */
    private fun transfer(payload: ByteArray): Received {
        val daemonInput = PipedInputStream(128 * 1024)
        val clientOutput = PipedOutputStream(daemonInput)
        val clientInput = PipedInputStream(1024)
        val daemonOutput = PipedOutputStream(clientInput)
        val daemon = CompletableFuture.supplyAsync {
            DataInputStream(daemonInput).use { input ->
                daemonOutput.use { output ->
                    fun readId(): String = String(ByteArray(4).also { input.readFully(it) }, Charsets.US_ASCII)
                    fun readLength(): Int = Integer.reverseBytes(input.readInt())
                    assertEquals("SEND", readId())
                    val requestLength = readLength()
                    assertTrue(requestLength in 1..1024)
                    val request = ByteArray(requestLength).also { input.readFully(it) }
                    assertEquals("${staging.apk},${0x8000 or 0x180}", String(request, Charsets.UTF_8))
                    val received = ByteArrayOutputStream()
                    val chunks = mutableListOf<Int>()
                    while (true) {
                        val id = readId()
                        val value = readLength()
                        if (id == "DONE") {
                            assertTrue(value > 0)
                            break
                        }
                        assertEquals("DATA", id)
                        assertTrue(value in 1..65536)
                        chunks += value
                        val bytes = ByteArray(value).also { input.readFully(it) }
                        received.write(bytes)
                        assertTrue(received.size() <= payload.size)
                    }
                    assertArrayEquals(payload, received.toByteArray())
                    // Fragment every acknowledgement byte to exercise read-exactly across reads.
                    for (byte in frame("OKAY", 0)) {
                        output.write(byte.toInt())
                        output.flush()
                    }
                    Received(received.toByteArray(), chunks)
                }
            }
        }
        try {
            val fragmentedResponse = object : FilterInputStream(clientInput) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                    super.read(bytes, offset, minOf(length, 1))
            }
            AdbSyncTransfer.send(ByteArrayInputStream(payload), payload.size.toLong(), staging.apk,
                0x180, fragmentedResponse, clientOutput) { }
            return daemon.get(5, TimeUnit.SECONDS)
        } finally {
            clientOutput.close()
            clientInput.close()
        }
    }

    @Test fun streamsActualBytesAcrossSyncDataBoundaries() {
        for (size in listOf(1, 65535, 65536, 65537, 131089)) {
            val payload = ByteArray(size) { ((it * 31) xor (it ushr 8)).toByte() }
            val received = transfer(payload)
            assertArrayEquals(payload, received.bytes)
            assertEquals((size + 65535) / 65536, received.chunks.size)
        }
    }

    private fun sendWithResponse(response: ByteArray, payload: ByteArray = byteArrayOf(1),
                                 size: Long = payload.size.toLong()) {
        AdbSyncTransfer.send(ByteArrayInputStream(payload), size, staging.apk, 0x180,
            ByteArrayInputStream(response), ByteArrayOutputStream()) { }
    }

    @Test fun rejectsNegativeOversizedUnexpectedAndTruncatedAcknowledgements() {
        for (response in listOf(frame("OKAY", 1), frame("FAIL", -1), frame("FAIL", 4097),
                frame("WHAT", 0), frame("FAIL", 4) + byteArrayOf(1), frame("OKAY", 0).copyOf(7))) {
            assertThrows(IOException::class.java) { sendWithResponse(response) }
        }
        val reason = "Permission denied".toByteArray()
        val error = assertThrows(IOException::class.java) {
            sendWithResponse(frame("FAIL", reason.size) + reason)
        }
        assertTrue(error.message!!.contains("Permission denied"))
    }

    @Test fun rejectsMissingOrAdditionalBytesInsteadOfAcknowledgingChangedApk() {
        assertThrows(EOFException::class.java) { sendWithResponse(frame("OKAY", 0), byteArrayOf(1), 2) }
        assertThrows(IOException::class.java) { sendWithResponse(frame("OKAY", 0), byteArrayOf(1, 2), 1) }
        for (size in listOf(0L, -1L, AdbSyncTransfer.MAX_FILE_SIZE + 1)) {
            assertThrows(IllegalArgumentException::class.java) {
                sendWithResponse(frame("OKAY", 0), byteArrayOf(1), size)
            }
        }
    }

    @Test fun cancellationDuringDataTransferNeverSendsDone() {
        val wire = ByteArrayOutputStream()
        var dataWritten = false
        val output = object : OutputStream() {
            override fun write(value: Int) { wire.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                wire.write(bytes, offset, length)
                if (length == 65536) dataWritten = true
            }
        }
        assertThrows(CancellationException::class.java) {
            AdbSyncTransfer.send(ByteArrayInputStream(ByteArray(131072)), 131072,
                staging.apk, 0x180, ByteArrayInputStream(frame("OKAY", 0)), output) {
                if (dataWritten) throw CancellationException("cancelled")
            }
        }
        val input = DataInputStream(ByteArrayInputStream(wire.toByteArray()))
        val first = ByteArray(4).also { input.readFully(it) }
        assertEquals("SEND", String(first))
        val request = Integer.reverseBytes(input.readInt())
        input.readFully(ByteArray(request))
        assertEquals("DATA", String(ByteArray(4).also { input.readFully(it) }))
        assertEquals(65536, Integer.reverseBytes(input.readInt()))
        input.readFully(ByteArray(65536))
        assertEquals(-1, input.read())
    }

    @Test fun cancellationWhileReadingPartialResponseIsObserved() {
        var reads = 0
        val response = object : ByteArrayInputStream(frame("OKAY", 0)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                reads++
                return super.read(bytes, offset, minOf(length, 1))
            }
        }
        assertThrows(CancellationException::class.java) {
            AdbSyncTransfer.send(ByteArrayInputStream(byteArrayOf(1)), 1, staging.apk, 0x180,
                response, ByteArrayOutputStream()) {
                if (reads == 3) throw CancellationException("cancelled")
            }
        }
        assertEquals(3, reads)
    }

    @Test fun stagingRejectsShellInjectionAndCannotUploadOutsideOwnDirectory() {
        for (token in listOf("", "../update", "a; touch /tmp/pwned", "a".repeat(31),
                "A".repeat(32), "0".repeat(32) + "\n")) {
            assertThrows(IllegalArgumentException::class.java) { AdbInstallStaging(token) }
        }
        for (path in listOf("/sdcard/update.apk", "${staging.apk};id", "${staging.apk}/../other",
                staging.apk.replace("update.apk", "another.apk"))) {
            val wire = ByteArrayOutputStream()
            assertThrows(IllegalArgumentException::class.java) {
                AdbSyncTransfer.send(ByteArrayInputStream(byteArrayOf(1)), 1, path, 0x180,
                    ByteArrayInputStream(frame("OKAY", 0)), wire) { }
            }
            assertEquals(0, wire.size())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdbSyncTransfer.send(ByteArrayInputStream(byteArrayOf(1)), 1, staging.apk, 0x1ff,
                ByteArrayInputStream(frame("OKAY", 0)), ByteArrayOutputStream()) { }
        }
    }

    @Test fun installerResultIsNotSuccessUntilAValidCompletedExitStatusExists() {
        assertNull(AdbInstallOutcome.parse("ICONGEN_PM_WAIT"))
        assertEquals(AdbInstallOutcome(0, "Success"),
            AdbInstallOutcome.parse("ICONGEN_PM_EXIT=0\nSuccess"))
        assertEquals(AdbInstallOutcome(1, "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]"),
            AdbInstallOutcome.parse("ICONGEN_PM_EXIT=1\nFailure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]"))
        for (invalid in listOf("", "Success", "ICONGEN_PM_EXIT=-1", "ICONGEN_PM_EXIT=256",
                "ICONGEN_PM_EXIT=0; id", "ICONGEN_PM_WAIT\nICONGEN_PM_EXIT=0")) {
            assertThrows(IOException::class.java) { AdbInstallOutcome.parse(invalid) }
        }
    }

    private fun frame(id: String, length: Int): ByteArray = ByteArray(8).also { bytes ->
        for (index in 0..3) {
            bytes[index] = id[index].code.toByte()
            bytes[index + 4] = (length ushr (index * 8)).toByte()
        }
    }
}
