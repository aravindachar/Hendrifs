package com.aravind.hendrifs.usb

/** A raw 64-byte packet pipe to the amp. Implementations are used from one worker thread. */
interface AmpTransport {
    val description: String
    fun write(packet: ByteArray)
    /** Returns one incoming packet, or null if none arrived within ~8 ms. Throws if the link died. */
    fun read(): ByteArray?
    fun close()
}
