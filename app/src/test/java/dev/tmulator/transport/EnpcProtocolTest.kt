package dev.tmulator.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnpcProtocolTest {

    private val identity = NetworkIdentity(
        mac = byteArrayOf(0x02, 0x11, 0x22, 0x33, 0x44, 0x55),
        ip = byteArrayOf(192.toByte(), 168.toByte(), 1, 42),
        netmask = byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 0),
        gateway = byteArrayOf(192.toByte(), 168.toByte(), 1, 1),
    )

    private fun query(function: ByteArray): ByteArray {
        val out = ByteArray(14)
        "EPSONQ".toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        function.copyInto(out, 6)
        return out
    }

    private fun command(function: Int, data: ByteArray = byteArrayOf(0)): ByteArray {
        val out = ByteArray(14 + data.size)
        "EPSONC".toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        out[6] = 3
        out[7] = 0
        out[8] = ((function ushr 8) and 0xFF).toByte()
        out[9] = (function and 0xFF).toByte()
        out[12] = ((data.size ushr 8) and 0xFF).toByte()
        out[13] = (data.size and 0xFF).toByte()
        data.copyInto(out, 14)
        return out
    }

    @Test
    fun `hold and release commands are acknowledged`() {
        for (function in intArrayOf(0x15, 0x16)) {
            val request = command(function)
            val response = EnpcProtocol.buildCommandResponse(request)!!
            assertEquals("EPSONc", String(response.copyOfRange(0, 6), Charsets.US_ASCII))
            assertArrayEquals(request.copyOfRange(6, 10), response.copyOfRange(6, 10))
            assertArrayEquals(byteArrayOf(0, 0, 0, 0), response.copyOfRange(10, 14))
        }
        assertNull(EnpcProtocol.buildCommandResponse(command(0x99)))
    }

    @Test
    fun `device name discovery responds with EPSONq payload`() {
        val response = EnpcProtocol.buildResponse(
            query(byteArrayOf(3, 0, 0, 0)),
            "TM-m30",
            identity,
            null,
        )
        assertTrue(response != null)
        assertEquals("EPSONq", String(response!!.copyOfRange(0, 6), Charsets.US_ASCII))
        assertArrayEquals(byteArrayOf(3, 0, 0, 0), response.copyOfRange(6, 10))
        val length = ((response[10].toInt() and 0xFF) shl 24) or
            ((response[11].toInt() and 0xFF) shl 16) or
            ((response[12].toInt() and 0xFF) shl 8) or
            (response[13].toInt() and 0xFF)
        assertEquals(133, length)
        assertEquals("TM-m30", String(response.copyOfRange(19, 25), Charsets.US_ASCII))
    }

    @Test
    fun `network discovery exposes IPv4 details`() {
        val response = EnpcProtocol.buildResponse(
            query(byteArrayOf(0, 0, 0, 0x10)),
            "TM-m30",
            identity,
            null,
        )!!
        assertEquals(23, response[13].toInt())
        assertArrayEquals(identity.mac, response.copyOfRange(15, 21))
        assertArrayEquals(identity.ip, response.copyOfRange(23, 27))
        assertArrayEquals(identity.netmask, response.copyOfRange(27, 31))
        assertArrayEquals(identity.gateway, response.copyOfRange(31, 35))
    }

    @Test
    fun `who is holding returns the active POS address`() {
        val holder = byteArrayOf(192.toByte(), 168.toByte(), 1, 50)
        val response = EnpcProtocol.buildResponse(
            query(byteArrayOf(3, 0, 0, 0x17)),
            "TM-m30",
            identity,
            holder,
        )!!
        assertArrayEquals(holder, response.copyOfRange(14, 18))
    }

    @Test
    fun `broadcast response has TM m30 sized payload`() {
        val response = EnpcProtocol.buildResponse(
            query(byteArrayOf(0, 0, 0, 0)),
            "TM-m30",
            identity,
            null,
        )!!
        assertEquals(54, response[13].toInt())
        assertEquals(68, response.size)
    }

    @Test
    fun `unknown and malformed packets are ignored`() {
        assertNull(EnpcProtocol.buildResponse(query(byteArrayOf(1, 2, 3, 4)), "TM-m30", identity, null))
        assertNull(EnpcProtocol.buildResponse(byteArrayOf(1, 2, 3), "TM-m30", identity, null))
        assertNull(EnpcProtocol.buildResponse("NOTQ".toByteArray() + ByteArray(20), "TM-m30", identity, null))
    }
}
