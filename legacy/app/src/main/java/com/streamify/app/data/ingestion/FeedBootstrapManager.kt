package com.streamify.app.data.ingestion
import android.content.Context
import com.streamify.app.data.models.AppMode
import com.streamify.app.data.models.Track
import com.streamify.app.ui.models.VirtualShelf
import com.streamify.app.ui.models.VirtualShelfTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import com.streamify.app.data.repository.TrackRepository
import com.streamify.app.data.NativeBridge

object FeedBootstrapManager {

    fun getContinuousFeed(mode: AppMode, context: Context): Flow<List<VirtualShelf>> = flow {
        val allTracks = TrackRepository.getAllTracks()
        val shelves = mutableListOf<VirtualShelf>()

        when (mode) {
            AppMode.SPOTIFY -> {
                // Spotify Vibe Shelves: Ingested Native Tracks + Daily Mixes 1-3
                val dbPath = context.getDatabasePath("streamify_universal.db").absolutePath
                val nativeTracks = NativeBridge.fetchVirtualShelf(dbPath)

                if (nativeTracks.isNotEmpty()) {
                    shelves.add(VirtualShelf("spt_native_lib", "Your Spotify Taste", "Ingested from your connected profile", null, nativeTracks.take(20)))
                }

                if (allTracks.isNotEmpty()) {
                    val dailyMix1 = allTracks.take(8).map { it.toVirtualShelfTrack("SPOTIFY") }
                    val dailyMix2 = allTracks.drop(8).take(8).map { it.toVirtualShelfTrack("SPOTIFY") }
                    val discover = allTracks.filter { it.isLiked }.map { it.toVirtualShelfTrack("SPOTIFY") }

                    shelves.add(VirtualShelf("spt_mix_1", "Daily Mix 1", "Curated for your morning vibe", null, dailyMix1))
                    if (dailyMix2.isNotEmpty()) {
                        shelves.add(VirtualShelf("spt_mix_2", "Daily Mix 2", "Cohesive energy & focus", null, dailyMix2))
                    }
                    if (discover.isNotEmpty()) {
                        shelves.add(VirtualShelf("spt_discover", "Discover Weekly", "Deep dive based on your listening", null, discover))
                    }
                }
            }
            AppMode.YOUTUBE_MUSIC -> {
                // YTM Shelves: Supermix, Quick Picks, Unreleased & Live
                if (allTracks.isNotEmpty()) {
                    val supermix = allTracks.shuffled().take(10).map { it.toVirtualShelfTrack("YTM") }
                    val quickPicks = allTracks.sortedByDescending { it.playCount }.take(8).map { it.toVirtualShelfTrack("YTM") }
                    val unreleased = allTracks.filter { it.title.contains("Live", ignoreCase = true) || it.title.contains("Remix", ignoreCase = true) }.map { it.toVirtualShelfTrack("YTM") }

                    shelves.add(VirtualShelf("ytm_supermix", "Supermix", "Endless mix blending all your tastes", null, supermix))
                    if (quickPicks.isNotEmpty()) {
                        shelves.add(VirtualShelf("ytm_quick_picks", "Quick Picks", "Start radio from these tracks", null, quickPicks))
                    }
                    if (unreleased.isNotEmpty()) {
                        shelves.add(VirtualShelf("ytm_live_gems", "Live & Remix Gems", "Special performances and rare editions", null, unreleased))
                    }
                }
            }
            AppMode.STREAMIFY -> {
                // Streamify 50/50 Autonomous Hybrid Continuum
                if (allTracks.isNotEmpty()) {
                    val hybridContinuum = allTracks.take(12).map { it.toVirtualShelfTrack("UNIFIED") }
                    val heavyRotation = allTracks.sortedByDescending { it.playCount }.take(10).map { it.toVirtualShelfTrack("UNIFIED") }
                    val acousticResonance = allTracks.filter { it.durationSec in 150..300 }.take(8).map { it.toVirtualShelfTrack("UNIFIED") }

                    shelves.add(VirtualShelf("stm_continuum", "50/50 Neural Continuum", "Harmonic Camelot & Spotify/YTM Hybrid Queue", null, hybridContinuum))
                    if (heavyRotation.isNotEmpty()) {
                        shelves.add(VirtualShelf("stm_rotation", "Heavy Rotation", "Your highest dwell-time tracks", null, heavyRotation))
                    }
                    if (acousticResonance.isNotEmpty()) {
                        shelves.add(VirtualShelf("stm_resonance", "Acoustic Resonance", "128-D Vector Match & Flow State", null, acousticResonance))
                    }
                }
            }
        }

        // Fallback default shelf if empty
        if (shelves.isEmpty()) {
            val fallbackTracks = allTracks.take(10).map { it.toVirtualShelfTrack("UNIFIED") }
            shelves.add(VirtualShelf("default_mix", "Trending Flow", "Popular audio streams", null, fallbackTracks))
        }

        emit(shelves)
    }.flowOn(Dispatchers.IO)

    private fun Track.toVirtualShelfTrack(origin: String): VirtualShelfTrack {
        val computedCadId = NativeBridge.generateCadId(this.title, this.artist, this.durationSec)
        return VirtualShelfTrack(
            cadId = if (computedCadId.isNotBlank()) computedCadId else "cad_${this.id}",
            title = this.title,
            artist = this.artist,
            artworkUrl = this.coverArtPath ?: "",
            durationSec = this.durationSec,
            isrc = this.isrc,
            ytmVideoId = this.ytmVideoId ?: if (this.filepath.startsWith("http")) null else this.filepath,
            isLiked = this.isLiked,
            platformOrigin = origin
        )
    }
}
