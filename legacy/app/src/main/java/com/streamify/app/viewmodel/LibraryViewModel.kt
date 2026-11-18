package com.streamify.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamify.app.data.repository.TrackRepository
import com.streamify.app.data.models.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class LibraryUiState {
    object Loading : LibraryUiState()
    data class Success(
        val tracks: List<Track>,
        val likedTracks: List<Track>
    ) : LibraryUiState()
    data class Error(val message: String) : LibraryUiState()
}

class LibraryViewModel(private val repository: com.streamify.app.data.repository.TrackRepositoryApi = com.streamify.app.data.repository.TrackRepository) : ViewModel() {

    private val _uiState = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.allTracks.collect { tracks ->
                val likedTracks = tracks.filter { it.isLiked }
                _uiState.value = LibraryUiState.Success(tracks = tracks, likedTracks = likedTracks)
            }
        }
        loadLibrary()
    }

    fun loadLibrary() {
        viewModelScope.launch {
            try {
                repository.refresh()
            } catch (e: Exception) {
                _uiState.value = LibraryUiState.Error(e.message ?: "Failed to load library")
            }
        }
    }
}
