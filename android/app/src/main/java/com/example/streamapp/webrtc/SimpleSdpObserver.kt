package com.example.streamapp.webrtc

import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/** Implementación base de SdpObserver que no hace nada por defecto. */
open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {}
}
