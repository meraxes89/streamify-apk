package com.maxrave.simpmusic.data

import com.maxrave.domain.data.model.update.UpdateData
import com.maxrave.domain.repository.UpdateRepository
import com.maxrave.domain.utils.Resource
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class StreamifyUpdateRepositoryImpl(
    private val httpClient: HttpClient = HttpClient(CIO),
) : UpdateRepository {

    override fun checkForGithubReleaseUpdate(): Flow<Resource<UpdateData>> =
        flow<Resource<UpdateData>> {
            try {
                val response = httpClient.get(GITHUB_API_LATEST_RELEASE) {
                    header(HttpHeaders.UserAgent, "Streamify-App")
                    header(HttpHeaders.Accept, "application/vnd.github.v3+json")
                }
                if (response.status == HttpStatusCode.OK) {
                    val bodyText = response.bodyAsText()
                    val json = Json { ignoreUnknownKeys = true }
                    val obj = json.parseToJsonElement(bodyText).jsonObject
                    val tagName = obj["tag_name"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content ?: ""
                    val publishedAt = obj["published_at"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                    val body = obj["body"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content ?: ""
                    emit(
                        Resource.Success(
                            UpdateData(
                                tagName = tagName,
                                releaseTime = publishedAt,
                                body = body,
                            ),
                        ),
                    )
                } else {
                    emit(Resource.Error<UpdateData>("GitHub API returned HTTP ${response.status.value}"))
                }
            } catch (e: Exception) {
                emit(Resource.Error<UpdateData>(e.message ?: "Failed to check for updates"))
            }
        }.flowOn(Dispatchers.IO)

    override fun checkForFdroidUpdate(): Flow<Resource<UpdateData>> =
        flow<Resource<UpdateData>> {
            emit(Resource.Error<UpdateData>("F-Droid updates not supported for Streamify"))
        }.flowOn(Dispatchers.IO)

    override fun getFdroidSigningKeys(): Flow<Resource<List<String>>> =
        flow<Resource<List<String>>> {
            emit(Resource.Success<List<String>>(emptyList()))
        }.flowOn(Dispatchers.IO)

    companion object {
        private const val GITHUB_API_LATEST_RELEASE =
            "https://api.github.com/repos/meraxes89/streamify-apk/releases/latest"
    }
}
