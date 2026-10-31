package com.theveloper.pixelplay.data.p2p

/**
 * Discovery seam so the protocol engine can run without Android NsdManager (unit-test
 * loopback harness). Production binding creates [NsdPeerDiscovery].
 */
interface PeerDiscoveryHandle {
    fun register(localName: String, port: Int)
    fun startBrowsing()
    fun stop()
}

fun interface PeerDiscoveryFactory {
    fun create(localId: String, listener: NsdPeerDiscovery.Listener): PeerDiscoveryHandle
}
