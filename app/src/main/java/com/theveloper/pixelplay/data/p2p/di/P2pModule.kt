package com.theveloper.pixelplay.data.p2p.di

import android.content.Context
import com.theveloper.pixelplay.data.p2p.MediaControllerCommandExecutor
import com.theveloper.pixelplay.data.p2p.MusicLibraryHashIndex
import com.theveloper.pixelplay.data.p2p.NsdPeerDiscovery
import com.theveloper.pixelplay.data.p2p.P2pCommandExecutor
import com.theveloper.pixelplay.data.p2p.P2pProtocolSpec
import com.theveloper.pixelplay.data.p2p.P2pSessionLibrary
import com.theveloper.pixelplay.data.p2p.P2pSessionPolicy
import com.theveloper.pixelplay.data.p2p.PauseOnPeerActivePolicy
import com.theveloper.pixelplay.data.p2p.PeerDiscoveryFactory
import com.theveloper.pixelplay.data.p2p.PeerWsClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object P2pModule {

    /** Peer dials ride the app's shared OkHttp client (connection pool, TLS, logging). */
    @Provides
    @Singleton
    fun providePeerWsClient(okHttpClient: OkHttpClient): PeerWsClient =
        PeerWsClient(okHttpClient, P2pProtocolSpec.DIAL_TIMEOUT_MS)

    /** Remote playback commands apply to the app-wide MediaController via PlaybackStateHolder. */
    @Provides
    @Singleton
    fun provideP2pCommandExecutor(playbackStateHolder: com.theveloper.pixelplay.presentation.viewmodel.PlaybackStateHolder): P2pCommandExecutor =
        MediaControllerCommandExecutor(playbackStateHolder)

    /** Production discovery = NsdManager-backed advertiser + browser. */
    @Provides
    @Singleton
    fun providePeerDiscoveryFactory(@ApplicationContext context: Context): PeerDiscoveryFactory =
        PeerDiscoveryFactory { localId, listener -> NsdPeerDiscovery(localId, context, listener) }

    /** Shared-session library: SHA-256 file hashing + hash->song index over the local library. */
    @Provides
    @Singleton
    fun provideP2pSessionLibrary(index: MusicLibraryHashIndex): P2pSessionLibrary = index

    /** Reference session policy: pause self when a peer takes over; takeover via MediaController. */
    @Provides
    @Singleton
    fun provideP2pSessionPolicy(playbackStateHolder: com.theveloper.pixelplay.presentation.viewmodel.PlaybackStateHolder): P2pSessionPolicy =
        PauseOnPeerActivePolicy(playbackStateHolder)
}
