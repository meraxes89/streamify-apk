package io.ktor.client.engine.cio

import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory

class CIOEngineConfig : HttpClientEngineConfig()

expect object CIO : HttpClientEngineFactory<CIOEngineConfig>
