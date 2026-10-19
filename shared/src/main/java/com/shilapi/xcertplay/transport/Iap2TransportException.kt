package com.shilapi.xcertplay.transport

import java.io.IOException

/** Failures raised by the iAP2 transport while bringing up or using a session. */
sealed class Iap2TransportException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class DeviceUnavailable(message: String, cause: Throwable? = null) :
        Iap2TransportException(message, cause)

    class TimedOut(message: String, cause: Throwable? = null) :
        Iap2TransportException(message, cause)

    class Protocol(message: String) : Iap2TransportException(message)
}
