package com.maxrave.simpmusic.data

import com.maxrave.domain.data.model.update.UpdateData
import com.maxrave.domain.repository.UpdateRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.simpmusic.BuildKonfig
import com.maxrave.simpmusic.data.update.UpdateResolver
import com.maxrave.simpmusic.data.update.UpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class StreamifyUpdateRepositoryImpl(
    private val resolver: UpdateResolver = UpdateResolver(),
) : UpdateRepository {

    override fun checkForGithubReleaseUpdate(): Flow<Resource<UpdateData>> =
        flow<Resource<UpdateData>> {
            try {
                val installedCode =
                    try {
                        BuildKonfig.versionCode
                    } catch (_: Exception) {
                        0
                    }

                when (val result = resolver.resolve(installedCode)) {
                    is UpdateResult.Available -> {
                        emit(
                            Resource.Success(
                                UpdateData(
                                    tagName = result.manifest.version,
                                    releaseTime = result.manifest.publishedAt,
                                    body = UpdateSanitizer.sanitizeChangelog(result.manifest.changelog),
                                ),
                            ),
                        )
                    }
                    is UpdateResult.TagOnly -> {
                        emit(
                            Resource.Success(
                                UpdateData(
                                    tagName = result.tag,
                                    releaseTime = null,
                                    body = "https://github.com/meraxes89/streamify-apk/releases/tag/${result.tag}",
                                ),
                            ),
                        )
                    }
                    is UpdateResult.UpToDate -> {
                        emit(Resource.Error<UpdateData>("App is up to date"))
                    }
                    is UpdateResult.Error -> {
                        emit(Resource.Error<UpdateData>(result.message))
                    }
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
}
