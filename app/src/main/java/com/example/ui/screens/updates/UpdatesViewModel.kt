package com.example.ui.screens.updates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.UpdateEntryEntity
import com.example.data.repository.MangaRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UpdatesUiState(
    val updates: List<UpdateEntryEntity> = emptyList(),
    val sources: List<String> = emptyList(),
    val mangaTitles: List<String> = emptyList(),
    val selectedSource: String? = null,
    val selectedMangaTitle: String? = null,
    val isChecking: Boolean = false,
    val statusMessage: String? = null
)

class UpdatesViewModel(
    private val mangaRepo: MangaRepository
) : ViewModel() {

    private val controls = MutableStateFlow(Controls())

    private data class Controls(
        val selectedSource: String? = null,
        val selectedMangaTitle: String? = null,
        val isChecking: Boolean = false,
        val statusMsg: String? = null
    )

    val uiState: StateFlow<UpdatesUiState> = combine(
        mangaRepo.updates,
        controls
    ) { allUpdates, ctrl ->
        val filtered = allUpdates.filter { u ->
            (ctrl.selectedSource == null || u.sourceName == ctrl.selectedSource) &&
                (ctrl.selectedMangaTitle == null || u.mangaTitle == ctrl.selectedMangaTitle)
        }
        UpdatesUiState(
            updates = filtered,
            sources = allUpdates.map { it.sourceName }.distinct().sorted(),
            mangaTitles = allUpdates.map { it.mangaTitle }.distinct().sorted(),
            selectedSource = ctrl.selectedSource,
            selectedMangaTitle = ctrl.selectedMangaTitle,
            isChecking = ctrl.isChecking,
            statusMessage = ctrl.statusMsg
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UpdatesUiState())

    fun checkForUpdatesNow() {
        viewModelScope.launch {
            controls.update { it.copy(isChecking = true, statusMsg = null) }
            val count = runCatching { mangaRepo.checkAllLibraryUpdates() }.getOrDefault(-1)
            controls.update {
                if (count >= 0) {
                    it.copy(isChecking = false, statusMsg = "Update check complete: $count new chapter(s) found.")
                } else {
                    it.copy(isChecking = false, statusMsg = "Failed to check updates. Verify connectivity.")
                }
            }
        }
    }

    fun selectSourceFilter(source: String?) {
        controls.update { it.copy(selectedSource = source) }
    }

    fun selectMangaFilter(title: String?) {
        controls.update { it.copy(selectedMangaTitle = title) }
    }

    fun markUpdateRead(entry: UpdateEntryEntity) {
        viewModelScope.launch {
            mangaRepo.setChapterRead(entry.chapterId, entry.mangaId, true)
            mangaRepo.markUpdateSeen(entry.id)
        }
    }

    fun markAllSeen() {
        viewModelScope.launch { mangaRepo.markAllUpdatesSeen() }
    }

    fun clearStatusMessage() {
        controls.update { it.copy(statusMsg = null) }
    }

    companion object {
        fun factory(mangaRepo: MangaRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    UpdatesViewModel(mangaRepo) as T
            }
    }
}
