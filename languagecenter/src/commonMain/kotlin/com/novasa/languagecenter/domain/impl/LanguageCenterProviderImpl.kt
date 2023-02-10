package com.novasa.languagecenter.domain.impl

import co.touchlab.kermit.Logger
import com.novasa.languagecenter.data.repository.LanguageCenterRepository
import com.novasa.languagecenter.domain.model.*
import com.novasa.languagecenter.domain.provider.LanguageCenterProvider
import com.novasa.languagecenter.extension.fullKey
import com.novasa.languagecenter.extension.string
import com.novasa.languagecenter.injection.LanguageCenterKoinComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.koin.core.component.inject

internal class LanguageCenterProviderImpl : LanguageCenterProvider, LanguageCenterKoinComponent() {

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    private lateinit var _config: LanguageCenterConfig
    override val config: LanguageCenterConfig
        get() = _config

    private lateinit var repository: LanguageCenterRepository

    private val _status = MutableStateFlow<LanguageCenterStatus>(LanguageCenterStatus.NotInitialized)
    override val status: StateFlow<LanguageCenterStatus> by lazy {
        _status.asStateFlow()
    }

    override val activeLanguage: StateFlow<LanguageCenterLanguage> by lazy {
        throwIfNotInitialized()
        repository.activeLanguage.stateIn(
            scope = coroutineScope,
            started = SharingStarted.Eagerly,
            initialValue = LanguageCenterLanguage.UNDEFINED
        )
    }

    private val translations: StateFlow<Map<String, LanguageCenterTranslation>?> by lazy {
        throwIfNotInitialized()
        repository.translations.stateIn(
            scope = coroutineScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )
    }

    private fun updateStatus(status: LanguageCenterStatus) {
        _status.update { status }
    }

    override fun initialize(config: LanguageCenterConfig) {
        _config = config
        repository = inject<LanguageCenterRepository>().value

        updateStatus(LanguageCenterStatus.Updating)

        coroutineScope.launch {
            try {
                repository.update()
                updateStatus(LanguageCenterStatus.Ready(activeLanguage.value))

            } catch (e: Exception) {
                Logger.e("Language center failed update", e)
                updateStatus(LanguageCenterStatus.Failure(e))
            }
        }
    }

    override fun setLanguage(language: String) {
        throwIfNotInitialized()
        updateStatus(LanguageCenterStatus.Updating)

        coroutineScope.launch {
            try {
                repository.setLanguage(language)
                updateStatus(LanguageCenterStatus.Ready(activeLanguage.value))
            } catch (e: Exception) {
                Logger.e("Language center failed to set language", e)
                updateStatus(LanguageCenterStatus.Failure(e))
            }
        }
    }

    override fun getTranslation(value: LanguageCenterValue): Flow<String> = translations
        .onStart {
            createTranslationIfNotExists(value)
        }
        .filterNotNull()
        .map { translations ->
            translations[value.fullKey]?.value ?: value.fallback
        }

    @OptIn(FlowPreview::class)
    private fun createTranslationIfNotExists(value: LanguageCenterValue) {
        coroutineScope.launch {
            try {
                val t = status
                    .filterIsInstance<LanguageCenterStatus.Ready>()
                    .flatMapMerge {
                        translations.filterNotNull()
                    }
                    .first()

                if (!t.containsKey(value.fullKey)) {
                    Logger.d("Translation for value ${value.string()} did not exist. Creating...")
                    repository.createTranslation(value)
                }

            } catch (e: Exception) {
                Logger.e("Failed to create translation", e)
            }
        }
    }

    private fun throwIfNotInitialized() {
        if (!::_config.isInitialized) {
            throw IllegalStateException("Language Center has not been initialized! Please call LanguageCenterProvider.initialize with the proper configuration.")
        }
    }
}
