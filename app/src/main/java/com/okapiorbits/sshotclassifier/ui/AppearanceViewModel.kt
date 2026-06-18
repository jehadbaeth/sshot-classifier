package com.okapiorbits.sshotclassifier.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.okapiorbits.sshotclassifier.data.prefs.AppTheme
import com.okapiorbits.sshotclassifier.data.prefs.UiPreferencesStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Supplies the theme choice to the Compose root and lets Settings change it. */
@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val uiPrefs: UiPreferencesStore,
) : ViewModel() {
    // Initial value matches the preference default so the first frame doesn't flash a wrong theme.
    val appTheme: StateFlow<AppTheme> =
        uiPrefs.appTheme.stateIn(viewModelScope, SharingStarted.Eagerly, AppTheme.DYNAMIC)
}
