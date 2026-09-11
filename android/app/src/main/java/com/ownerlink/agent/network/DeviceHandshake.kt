package com.ownerlink.agent.network

enum class DeviceHandshake { UNPAIRED_HELLO, DEVICE_AUTH }

fun requiredHandshake(paired: Boolean, sessionToken: String?): DeviceHandshake =
    if (paired && !sessionToken.isNullOrBlank()) DeviceHandshake.DEVICE_AUTH else DeviceHandshake.UNPAIRED_HELLO
