package com.yomitanmobile.domain.usecase

import com.yomitanmobile.data.local.entity.DictionaryInfo
import com.yomitanmobile.domain.model.ImportProgress
import com.yomitanmobile.domain.model.ImportResult
import com.yomitanmobile.domain.repository.DictionaryRepository
import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import javax.inject.Inject

class ImportDictionaryUseCase @Inject constructor(
    private val repository: DictionaryRepository
) {
    suspend fun invoke(
        inputStream: InputStream,
        onProgress: (ImportProgress) -> Unit = {}
    ): ImportResult {
        return repository.importDictionary(inputStream, onProgress)
    }
}

class DeleteDictionaryUseCase @Inject constructor(
    private val repository: DictionaryRepository
) {
    suspend fun invoke(dictionaryName: String) {
        repository.deleteDictionary(dictionaryName)
    }
}

class GetDictionariesUseCase @Inject constructor(
    private val repository: DictionaryRepository
) {
    fun invoke(): Flow<List<DictionaryInfo>> {
        return repository.getImportedDictionaries()
    }
}
