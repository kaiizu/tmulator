package dev.tmulator.transport

/** A bidirectional byte channel carrying a raw ESC/POS print job. */
interface PrintPeer {
    val key: String
    val remoteAddress: String
    fun write(bytes: ByteArray)
}
