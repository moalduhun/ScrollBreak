package com.moalduhun.scrollbreak.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moalduhun.scrollbreak.data.BlockerRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BlockerRepository(application)

    val isBlockingEnabled: StateFlow<Boolean> = repository.isBlockingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val todayBlockedCount: StateFlow<Int> = repository.todayBlockedCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val totalBlockedCount: StateFlow<Int> = repository.totalBlockedCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setBlockingEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setBlockingEnabled(enabled) }
    }
}
