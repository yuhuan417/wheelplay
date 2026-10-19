package com.shilapi.xcertplay.web

enum class StreamTechnology(val label: String) {
    WEBRTC("WebRTC 视频直通（自动回退）"),
    WEBCODECS("WebCodecs 视频流（WebSocket 兼容模式）"),
    JPEG("JPEG（兼容模式）"),
}
