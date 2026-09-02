package com.fakegps.mocklocation.vpn

data class IpNode(
    val id: String,
    val name: String,
    val country: String,
    val countryCode: String,
    val flagEmoji: String,
    val city: String,
    val latitude: Double,
    val longitude: Double,
    val virtualIp: String,
    val pingMs: Int,
    val isAvailable: Boolean = true
)

data class PublicIpInfo(
    val ip: String,
    val country: String = "Unknown",
    val countryCode: String = "UN",
    val city: String = "Unknown",
    val isp: String = "Unknown",
    val isMasked: Boolean = false
)
