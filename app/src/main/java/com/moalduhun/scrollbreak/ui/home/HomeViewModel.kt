package com.moalduhun.scrollbreak.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moalduhun.scrollbreak.data.BlockerRepository
import com.moalduhun.scrollbreak.data.DailyBlocks
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BlockerRepository(application)

    val isBlockingEnabled: StateFlow<Boolean> = repository.isBlockingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val coverInstagram: StateFlow<Boolean> = repository.coverInstagram
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val coverYouTube: StateFlow<Boolean> = repository.coverYouTube
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val coverTiktok: StateFlow<Boolean> = repository.coverTiktok
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val todayBlockedCount: StateFlow<Int> = repository.todayBlockedCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val totalBlockedCount: StateFlow<Int> = repository.totalBlockedCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val weeklyBlocks: StateFlow<List<DailyBlocks>> = repository.weeklyBlocks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setBlockingEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setBlockingEnabled(enabled) }
    }

    fun setCoverInstagram(enabled: Boolean) {
        viewModelScope.launch { repository.setCoverInstagram(enabled) }
    }

    fun setCoverYouTube(enabled: Boolean) {
        viewModelScope.launch { repository.setCoverYouTube(enabled) }
    }

    fun setCoverTiktok(enabled: Boolean) {
        viewModelScope.launch { repository.setCoverTiktok(enabled) }
    }
}
