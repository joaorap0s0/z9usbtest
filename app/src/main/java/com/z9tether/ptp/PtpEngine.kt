package com.z9tether.ptp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PtpEngine(
    private val connection: UsbDeviceConnection,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint,
    private val endpointEvent: UsbEndpoint? = null
) {
    private var transactionId = 1

    fun openSession(): Boolean {
        val payload = ByteBuffer.allocate(16).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(16)
            putShort(1)
            putShort(0x1002.toShort())
            putInt(transactionId++)
            putInt(1)
        }.array()

        val sent = connection.bulkTransfer(endpointOut, payload, payload.size, 1000)
        if (sent <= 0) return false

        val response = ByteArray(512)
        val read = connection.bulkTransfer(endpointIn, response, response.size, 1000)
        return read >= 12
    }

    fun getLatestObjectHandle(): Int? {
        val payload = ByteBuffer.allocate(20).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(20)
            putShort(1)
            putShort(0x1007.toShort())
            putInt(transactionId++)
            putInt(0xFFFFFFFF.toInt())
            putInt(0x0000)
            putInt(0x0000)
        }.array()

        if (connection.bulkTransfer(endpointOut, payload, payload.size, 1000) <= 0) return null

        val buffer = ByteArray(4096)
        val read = connection.bulkTransfer(endpointIn, buffer, buffer.size, 2000)
        if (read < 12) return null

        val bb = ByteBuffer.wrap(buffer, 0, read).order(ByteOrder.LITTLE_ENDIAN)
        val length = bb.int
        val type = bb.short

        if (type.toInt() == 2 && length > 12) {
            val numHandles = (length - 12) / 4
            if (numHandles > 0) {
                val handles = IntArray(numHandles)
                for (i in 0 until numHandles) {
                    handles[i] = bb.int
                }
                return handles.last()
            }
        }
        return null
    }

    fun fetchBitmap(handle: Int): Bitmap? {
        val payload = ByteBuffer.allocate(16).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(16)
            putShort(1)
            putShort(0x1009.toShort())
            putInt(transactionId++)
            putInt(handle)
        }.array()

        if (connection.bulkTransfer(endpointOut, payload, payload.size, 1000) <= 0) return null

        val headerBuffer = ByteArray(512)
        val headerRead = connection.bulkTransfer(endpointIn, headerBuffer, headerBuffer.size, 3000)
        if (headerRead < 12) return null

        val headerBb = ByteBuffer.wrap(headerBuffer, 0, headerRead).order(ByteOrder.LITTLE_ENDIAN)
        val totalLength = headerBb.int

        if (totalLength <= 12) return null

        val imageBytes = ByteArray(totalLength - 12)
        var bytesRead = headerRead - 12
        System.arraycopy(headerBuffer, 12, imageBytes, 0, bytesRead)

        val chunkSize = 16384
        val tempBuffer = ByteArray(chunkSize)

        while (bytesRead < imageBytes.size) {
            val toRead = minOf(chunkSize, imageBytes.size - bytesRead)
            val read = connection.bulkTransfer(endpointIn, tempBuffer, toRead, 3000)
            if (read <= 0) break
            System.arraycopy(tempBuffer, 0, imageBytes, bytesRead, read)
            bytesRead += read
        }

        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }
}
