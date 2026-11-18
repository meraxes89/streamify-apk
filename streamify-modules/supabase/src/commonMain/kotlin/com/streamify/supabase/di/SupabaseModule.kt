package com.streamify.supabase.di

import com.streamify.supabase.HybridHomeRepository
import com.streamify.supabase.SupabaseAdminClient
import com.streamify.supabase.SupabaseClient
import com.streamify.supabase.SupabaseFriendSync
import com.streamify.supabase.SupabaseLibrarySync
import com.streamify.supabase.SupabaseWrappedSync
import org.koin.dsl.module

val supabaseModule = module {
    single { SupabaseClient }
    single { SupabaseAdminClient }
    single { SupabaseWrappedSync }
    single { HybridHomeRepository }
    single { SupabaseLibrarySync }
    single { SupabaseFriendSync }
}
