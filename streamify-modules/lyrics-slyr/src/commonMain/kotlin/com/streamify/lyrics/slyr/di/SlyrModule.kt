package com.streamify.lyrics.slyr.di

import com.streamify.lyrics.slyr.LyricsPlaybackEngine
import com.streamify.lyrics.slyr.SlyrBytecodeCompiler
import org.koin.dsl.module

val slyrModule = module {
    single<SlyrBytecodeCompiler> { SlyrBytecodeCompiler }
    single<LyricsPlaybackEngine> { LyricsPlaybackEngine }
}
