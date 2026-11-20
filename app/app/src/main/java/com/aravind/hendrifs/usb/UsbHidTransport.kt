package com.aravind.hendrifs.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import com.aravind.hendrifs.protocol.IdCoreProtocol
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

/**
 * Talks to the amp's vendor HID interface over USB-OTG. Only the HID interface is
 * claimed, so the amp's USB-audio interfaces are left alone.
 */
class UsbHidTransport(manager: UsbManager, device: UsbDevice) : AmpTransport {
    private val connection: UsbDeviceConnection
    private val hid: UsbInterface
    private val epIn: UsbEndpoint
    private val epOut: UsbEndpoint?
    private val request: UsbRequest
    private val buffer = ByteBuffer.allocateDirect(IdCoreProtocol.PACKET)
    private var queued = false

    override val description: String

    init {
        hid = (0 until device.interfaceCount).map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
            ?: throw IOException("No HID interface on ${device.productName ?: device.deviceName}")
        val eps = (0 until hid.endpointCount).map { hid.getEndpoint(it) }
        epIn = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_INT }
            ?: throw IOException("No HID IN endpoint")
        epOut = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_INT }
        connection = manager.openDevice(device) ?: throw IOException("USB permission missing or device busy")
        if (!connection.claimInterface(hid, true)) {
            connection.close(); throw IOException("Could not claim HID interface ${hid.id}")
        }
        request = UsbRequest().apply { initialize(connection, epIn) }
        val model = IdCoreProtocol.MODELS[device.productId] ?: "Blackstar 0x%04X".format(device.productId)
        description = "$model · HID if ${hid.id} · IN 0x%02X · OUT %s".format(
            epIn.address, epOut?.let { "0x%02X".format(it.address) } ?: "SET_REPORT")
    }

    override fun write(packet: ByteArray) {
        // Prefer the interrupt OUT endpoint; fall back to HID SET_REPORT (output report, id 0).
        var n = epOut?.let { connection.bulkTransfer(it, packet, packet.size, 250) } ?: -1
        if (n < 0) n = connection.controlTransfer(0x21, 0x09, 0x0200, hid.id, packet, packet.size, 250)
        if (n != packet.size) throw IOException("USB write failed ($n/${packet.size})")
    }

    override fun read(): ByteArray? {
        if (!queued) {
            buffer.clear()
            if (!request.queue(buffer)) throw IOException("Could not queue HID read")
            queued = true
        }
        val done = try { connection.requestWait(8) } catch (_: TimeoutException) { return null }
            ?: throw IOException("USB connection lost")
        if (done !== request) return null
        queued = false
        buffer.flip()
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    override fun close() {
        runCatching { request.cancel() }
        runCatching { request.close() }
        runCatching { connection.releaseInterface(hid) }
        runCatching { connection.close() }
    }
}
