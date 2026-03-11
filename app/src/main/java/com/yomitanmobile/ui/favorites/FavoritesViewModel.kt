package com.yomitanmobile.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomitanmobile.data.local.dao.FavoriteWordDao
import com.yomitanmobile.data.local.entity.FavoriteWord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoriteWordDao: FavoriteWordDao
) : ViewModel() {

    val favorites: StateFlow<List<FavoriteWord>> = favoriteWordDao
        .getAllFavorites()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteFavorite(favorite: FavoriteWord) {
        viewModelScope.launch {
            favoriteWordDao.delete(favorite.expression, favorite.reading)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            favoriteWordDao.deleteAll()
        }
    }
}
