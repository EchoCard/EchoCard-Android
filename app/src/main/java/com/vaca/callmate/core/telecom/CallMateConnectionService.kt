package com.vaca.callmate.core.telecom

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle

/**
 * 与 iOS CallKit 等价：处理「延迟测试」自管理来电；其它请求失败返回。
 */
class CallMateConnectionService : ConnectionService() {

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val addr = request?.address
        val part = addr?.schemeSpecificPart
        if (addr?.scheme == PhoneAccount.SCHEME_TEL && part == "latencytest") {
            return LatencyTestConnection(this)
        }
        return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        super.onCreateIncomingConnectionFailed(connectionManagerPhoneAccount, request)
        LatencyTestCallBridge.onFailed?.invoke("Incoming connection failed")
    }
}
