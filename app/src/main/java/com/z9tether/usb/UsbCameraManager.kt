package com.z9tether.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

class UsbCameraManager(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    data class CameraConnection(
        val connection: UsbDeviceConnection,
        val endpointIn: UsbEndpoint,
        val endpointOut: UsbEndpoint,
        val endpointEvent: UsbEndpoint?
    )

    fun findNikonDevice(): UsbDevice? {
        val deviceList = usbManager.deviceList
        for (device in deviceList.values) {
            if (device.vendorId == 1200 || isPtpDevice(device)) {
                return device
            }
        }
        return null
    }

    private fun isPtpDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) {
                return true
            }
        }
        return false
    }

    fun openCameraConnection(device: UsbDevice): CameraConnection? {
        var ptpInterface: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) {
                ptpInterface = intf
                break
            }
        }
        ptpInterface = ptpInterface ?: device.getInterface(0)

        val connection = usbManager.openDevice(device) ?: return null
        if (!connection.claimInterface(ptpInterface, true)) {
            connection.close()
            return null
        }

        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        var epEvent: UsbEndpoint? = null

        for (i in 0 until ptpInterface.endpointCount) {
            val ep = ptpInterface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) {
                    epIn = ep
                } else {
                    epOut = ep
                }
            } else if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                epEvent = ep
            }
        }

        if (epIn == null || epOut == null) {
            connection.releaseInterface(ptpInterface)
            connection.close()
            return null
        }

        return CameraConnection(connection, epIn, epOut, epEvent)
    }
}
