package com.yomitanmobile.domain.usecase

import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.domain.repository.DictionaryRepository
import javax.inject.Inject

class GetWordDetailUseCase @Inject constructor(
    private val repository: DictionaryRepository
) {
    suspend fun invoke(entryId: Long): WordEntry? {
        return repository.getEntry(entryId)
    }
}
