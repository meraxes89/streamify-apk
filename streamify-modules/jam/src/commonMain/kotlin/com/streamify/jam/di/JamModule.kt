package com.streamify.jam.di

import com.streamify.core.di.streamifyCoreBridgeModule
import com.streamify.jam.BlendGenerator
import com.streamify.jam.StreamifyJamCoordinator
import com.streamify.lyrics.slyr.di.slyrModule
import com.streamify.media.hal.di.mediaHalModule
import com.streamify.supabase.di.supabaseModule
import org.koin.core.module.Module
import org.koin.dsl.module

val jamModule = module {
    single<BlendGenerator> { BlendGenerator }
    single<StreamifyJamCoordinator> { StreamifyJamCoordinator }
}

val streamifyAllModules: List<Module> = listOf(
    streamifyCoreBridgeModule,
    mediaHalModule,
    slyrModule,
    supabaseModule,
    jamModule
)
