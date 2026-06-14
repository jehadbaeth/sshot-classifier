package com.okapiorbits.sshotclassifier.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.okapiorbits.sshotclassifier.data.db.entity.TagEntity
import com.okapiorbits.sshotclassifier.data.repository.ScreenshotRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the screenshot detail / tag editor. Stateless with respect to which
 * screenshot is shown: the screen passes the id into each call and observes
 * [tags] keyed by that id, so a single VM instance serves any opened screenshot
 * without needing nav-args / SavedStateHandle.
 */
@HiltViewModel
class ScreenshotDetailViewModel @Inject constructor(
    private val repository: ScreenshotRepository,
) : ViewModel() {

    fun tags(screenshotId: Long): Flow<List<TagEntity>> = repository.observeTags(screenshotId)

    fun addTag(screenshotId: Long, label: String) {
        viewModelScope.launch { repository.addUserTag(screenshotId, label) }
    }

    fun removeTag(tag: TagEntity) {
        viewModelScope.launch { repository.removeTag(tag.id, tag.screenshot_id) }
    }
}
