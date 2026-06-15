package de.lwp2070809.speculonic.playback

import android.content.Context
import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.extractor.DefaultExtractorsFactory
import de.lwp2070809.speculonic.data.CacheManager
import de.lwp2070809.speculonic.di.NetworkModule

@UnstableApi
class PlayerBuilder(private val context: Context) {
    
    fun build(
        playbackCache: androidx.media3.datasource.cache.Cache,
        downloadCache: androidx.media3.datasource.cache.Cache,
        handleAudioFocus: Boolean = true,
        checkRestriction: () -> Boolean
    ): ExoPlayer {
        
        val okHttpClient = NetworkModule.provideOkHttpClient()
        val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)

        val restrictedHttpDataSourceFactory = DataSource.Factory {
            val upstream = httpDataSourceFactory.createDataSource()
            NetworkRestrictedDataSource(upstream, checkRestriction)
        }

        
        
        
        

        val playbackDataSinkFactory = CacheDataSink.Factory()
            .setCache(playbackCache)
            .setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE)

        
        val playbackDataSourceFactory = CacheDataSource.Factory()
            .setCache(playbackCache)
            .setUpstreamDataSourceFactory(restrictedHttpDataSourceFactory)
            .setCacheWriteDataSinkFactory(playbackDataSinkFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        
        
        
        val persistentDataSourceFactory = CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(playbackDataSourceFactory) 
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        
        val fallbackDataSourceFactory = DataSource.Factory {
            val upstream = persistentDataSourceFactory.createDataSource()
            LocalFallbackDataSource(context, upstream)
        }

        val extractorsFactory = DefaultExtractorsFactory()

        
        
        
        
        
        
        
        
        
        
        val mediaSourceFactory = ProgressiveMediaSource.Factory(
            fallbackDataSourceFactory, extractorsFactory
        )

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
            
        
        val (minBuffer, maxBuffer, playBuffer, rebuffer) = listOf(15_000, 30_000, 1_500, 2_500)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuffer, maxBuffer, playBuffer, rebuffer)
            .build()

        
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>
            ) {
                
            }

            override fun buildTextRenderers(
                context: Context,
                output: TextOutput,
                outputLooper: android.os.Looper,
                extensionRendererMode: Int,
                out: ArrayList<Renderer>
            ) {
                
            }
        }

        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, handleAudioFocus)
            .setLoadControl(loadControl)
            .build()
    }
}
