package com.example.mydung

import com.example.mydung.server.Config
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class ConnectionState {
    NOT_PAIRED,
    PAIRED_NOT_CONNECTED,
    CONNECTED,
    ERROR,
}

object PairingState {
    val stateFlow = MutableStateFlow(ConnectionState.NOT_PAIRED)
    val statusTextFlow = MutableStateFlow("Chưa ghép cặp")
    val logFlow = MutableSharedFlow<String>(extraBufferCapacity = 100)

    val discoveredPairingPortFlow = MutableStateFlow(-1)
    val discoveredConnectPortFlow = MutableStateFlow(-1)

    fun log(msg: String) {
        val ts = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        // Che token (32 hex) ở sink log của app: thông điệp ngoại lệ có thể
        // mang theo lệnh shell chứa token (lệnh khởi động Server).
        logFlow.tryEmit("[$ts] ${Config.redactSecrets(msg)}")
    }
}
