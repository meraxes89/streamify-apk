package com.streamify.supabase.di

import com.streamify.supabase.HybridHomeRepository
import com.streamify.supabase.SupabaseAdminClient
import com.streamify.supabase.SupabaseClient
import com.streamify.supabase.SupabaseFriendSync
import com.streamify.supabase.SupabaseLibrarySync
import com.streamify.supabase.SupabaseWrappedSync
import org.koin.dsl.module

val supabaseModule = module {
    single<SupabaseClient> { SupabaseClient }
    single<SupabaseAdminClient> { SupabaseAdminClient }
    single<SupabaseWrappedSync> { SupabaseWrappedSync }
    single<HybridHomeRepository> { HybridHomeRepository }
    single<SupabaseLibrarySync> { SupabaseLibrarySync }
    single<SupabaseFriendSync> { SupabaseFriendSync }
}
