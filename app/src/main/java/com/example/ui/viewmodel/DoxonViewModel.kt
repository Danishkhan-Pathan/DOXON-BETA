package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.database.ChatMessage
import com.example.data.database.ChatSession
import com.example.data.database.DoxonDatabase
import com.example.data.repository.ChatRepository
import com.example.util.HapticHelper
import com.example.util.SelfHealingMonitor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DoxonViewModel(application: Application) : AndroidViewModel(application) {
    private val doxonDao = DoxonDatabase.getDatabase(application).doxonDao()
    private val repository = ChatRepository(application, doxonDao)
    val hapticHelper = HapticHelper(application)

    private val _isIncognito = MutableStateFlow(false)
    val isIncognito: StateFlow<Boolean> = _isIncognito.asStateFlow()

    val cloudSyncManager = com.example.data.sync.CloudSyncManager(application, doxonDao)

    private val _authenticatedEmail = MutableStateFlow<String?>(cloudSyncManager.getAuthenticatedEmail())
    val authenticatedEmail: StateFlow<String?> = _authenticatedEmail.asStateFlow()

    private val _profileDisplayName = MutableStateFlow<String>(
        cloudSyncManager.getDisplayName() ?: (cloudSyncManager.getAuthenticatedEmail()?.substringBefore("@") ?: "User")
    )
    val profileDisplayName: StateFlow<String> = _profileDisplayName.asStateFlow()

    private val _profilePicture = MutableStateFlow<String>(
        cloudSyncManager.getProfilePicture() ?: "avatar_default"
    )
    val profilePicture: StateFlow<String> = _profilePicture.asStateFlow()

    val authenticatedUser: StateFlow<String?> = _profileDisplayName.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastSyncedTime = MutableStateFlow(cloudSyncManager.getLastSyncedTime())
    val lastSyncedTime: StateFlow<Long> = _lastSyncedTime.asStateFlow()

    private val _isAutoSyncEnabled = MutableStateFlow(cloudSyncManager.isAutoSyncEnabled())
    val isAutoSyncEnabled: StateFlow<Boolean> = _isAutoSyncEnabled.asStateFlow()

    private val _isSearchingLive = MutableStateFlow(false)
    val isSearchingLive: StateFlow<Boolean> = _isSearchingLive.asStateFlow()

    private val _isSearchGroundingEnabled = MutableStateFlow(
        application.getSharedPreferences("doxon_settings", Context.MODE_PRIVATE)
            .getBoolean("search_grounding_enabled", true)
    )
    val isSearchGroundingEnabled: StateFlow<Boolean> = _isSearchGroundingEnabled.asStateFlow()

    fun setSearchGroundingEnabled(enabled: Boolean) {
        _isSearchGroundingEnabled.value = enabled
        getApplication<Application>().getSharedPreferences("doxon_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("search_grounding_enabled", enabled).apply()
        hapticHelper.triggerVibration()
    }

    private val _isLowLatency = MutableStateFlow(
        application.getSharedPreferences("doxon_settings", Context.MODE_PRIVATE)
            .getBoolean("is_low_latency", false)
    )
    val isLowLatency: StateFlow<Boolean> = _isLowLatency.asStateFlow()

    fun setLowLatency(enabled: Boolean) {
        _isLowLatency.value = enabled
        getApplication<Application>().getSharedPreferences("doxon_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("is_low_latency", enabled).apply()
        hapticHelper.triggerVibration()
    }

    private val _isHighThinking = MutableStateFlow(
        application.getSharedPreferences("doxon_settings", Context.MODE_PRIVATE)
            .getBoolean("is_high_thinking", false)
    )
    val isHighThinking: StateFlow<Boolean> = _isHighThinking.asStateFlow()

    fun setHighThinking(enabled: Boolean) {
        _isHighThinking.value = enabled
        getApplication<Application>().getSharedPreferences("doxon_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("is_high_thinking", enabled).apply()
        hapticHelper.triggerVibration()
    }

    private val _isMapsGroundingEnabled = MutableStateFlow(
        application.getSharedPreferences("doxon_settings", Context.MODE_PRIVATE)
            .getBoolean("is_maps_grounding", false)
    )
    val isMapsGroundingEnabled: StateFlow<Boolean> = _isMapsGroundingEnabled.asStateFlow()

    fun setMapsGroundingEnabled(enabled: Boolean) {
        _isMapsGroundingEnabled.value = enabled
        getApplication<Application>().getSharedPreferences("doxon_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("is_maps_grounding", enabled).apply()
        hapticHelper.triggerVibration()
    }

    private val _aiResponseLength = MutableStateFlow(
        application.getSharedPreferences("doxon_settings", Context.MODE_PRIVATE)
            .getString("ai_response_length", "balanced") ?: "balanced"
    )
    val aiResponseLength: StateFlow<String> = _aiResponseLength.asStateFlow()

    private val _aiCreativity = MutableStateFlow(
        application.getSharedPreferences("doxon_settings", Context.MODE_PRIVATE)
            .getString("ai_creativity", "balanced") ?: "balanced"
    )
    val aiCreativity: StateFlow<String> = _aiCreativity.asStateFlow()

    private val _customAiPersona = MutableStateFlow(
        application.getSharedPreferences("doxon_settings", Context.MODE_PRIVATE)
            .getString("custom_ai_persona", "") ?: ""
    )
    val customAiPersona: StateFlow<String> = _customAiPersona.asStateFlow()

    fun setAiResponseLength(length: String) {
        _aiResponseLength.value = length
        getApplication<Application>().getSharedPreferences("doxon_settings", Context.MODE_PRIVATE)
            .edit().putString("ai_response_length", length).apply()
        if (cloudSyncManager.getAuthenticatedEmail() != null && cloudSyncManager.isAutoSyncEnabled()) {
            triggerCloudSync()
        }
        hapticHelper.triggerVibration()
    }

    fun setAiCreativity(creativity: String) {
        _aiCreativity.value = creativity
        getApplication<Application>().getSharedPreferences("doxon_settings", Context.MODE_PRIVATE)
            .edit().putString("ai_creativity", creativity).apply()
        if (cloudSyncManager.getAuthenticatedEmail() != null && cloudSyncManager.isAutoSyncEnabled()) {
            triggerCloudSync()
        }
        hapticHelper.triggerVibration()
    }

    fun setCustomAiPersona(persona: String) {
        _customAiPersona.value = persona
        getApplication<Application>().getSharedPreferences("doxon_settings", Context.MODE_PRIVATE)
            .edit().putString("custom_ai_persona", persona).apply()
        if (cloudSyncManager.getAuthenticatedEmail() != null && cloudSyncManager.isAutoSyncEnabled()) {
            triggerCloudSync()
        }
        hapticHelper.triggerVibration()
    }

    fun setProfileDetails(displayName: String, avatar: String) {
        cloudSyncManager.saveDisplayName(displayName)
        cloudSyncManager.saveProfilePicture(avatar)
        _profileDisplayName.value = displayName
        _profilePicture.value = avatar
        
        // Trigger auto-sync if authenticated
        if (cloudSyncManager.getAuthenticatedEmail() != null && cloudSyncManager.isAutoSyncEnabled()) {
            triggerCloudSync()
        }
        hapticHelper.triggerVibration()
    }

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _currentSession = MutableStateFlow<ChatSession?>(null)
    val currentSession: StateFlow<ChatSession?> = _currentSession.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _isOffline = MutableStateFlow(!repository.isNetworkAvailable())
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    fun isNetworkAvailable(): Boolean = repository.isNetworkAvailable()

    private val _showNetworkToast = MutableStateFlow(false)
    val showNetworkToast: StateFlow<Boolean> = _showNetworkToast.asStateFlow()

    private var networkToastJob: Job? = null

    fun triggerNetworkToast() {
        networkToastJob?.cancel()
        _showNetworkToast.value = true
        networkToastJob = viewModelScope.launch {
            delay(5500)
            _showNetworkToast.value = false
        }
    }

    private val _themeMode = MutableStateFlow(
        application.getSharedPreferences("doxon_settings", Context.MODE_PRIVATE)
            .getString("theme_mode", "system") ?: "system"
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _dismissedTabs = MutableStateFlow<Set<String>>(emptySet())
    val dismissedTabs: StateFlow<Set<String>> = _dismissedTabs.asStateFlow()

    private val _activeTab = MutableStateFlow<String?>(null)
    val activeTab: StateFlow<String?> = _activeTab.asStateFlow()

    private val _lastDismissedTab = MutableStateFlow<String?>(null)
    val lastDismissedTab: StateFlow<String?> = _lastDismissedTab.asStateFlow()

    // Exposing the health tracking vectors to the UI
    val securityLogs: StateFlow<List<String>> = SelfHealingMonitor.securityLogs
    val isSystemHealthy: StateFlow<Boolean> = SelfHealingMonitor.isHealthy

    private var activeGenerationJob: Job? = null
    private var messagesCollectionJob: Job? = null

    init {
        restoreSavedState()

        viewModelScope.launch {
            _isIncognito.collectLatest { incog ->
                repository.getSessions(incog).collect { list ->
                    _sessions.value = list
                }
            }
        }

        // Network monitoring loop
        viewModelScope.launch {
            while (true) {
                delay(3000)
                val currNetwork = !repository.isNetworkAvailable()
                if (currNetwork != _isOffline.value) {
                    _isOffline.value = currNetwork
                    if (!currNetwork) {
                        triggerAutoReconnectHandshake()
                    }
                }
            }
        }

        // Thread Integrity & Memory Allocation leak monitoring loop
        viewModelScope.launch {
            while (true) {
                delay(12000)
                SelfHealingMonitor.performBackgroundAudit(getApplication())
            }
        }

        viewModelScope.launch {
            repository.cleanExpiredIncognitoSessions()
        }
    }

    private fun restoreSavedState() {
        val prefs = getApplication<Application>().getSharedPreferences("doxon_settings", Context.MODE_PRIVATE)
        val savedSessionId = prefs.getLong("saved_session_id", -1L)
        val savedIsIncognito = prefs.getBoolean("saved_is_incognito", false)
        _isIncognito.value = savedIsIncognito
        
        if (savedSessionId != -1L) {
            viewModelScope.launch {
                val session = repository.getSessionById(savedSessionId)
                if (session != null) {
                    selectSession(session)
                }
            }
        }
    }

    fun saveInputPrompt(text: String) {
        val prefs = getApplication<Application>().getSharedPreferences("doxon_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("saved_input_prompt", text).apply()
    }

    fun getSavedInputPrompt(): String {
        val prefs = getApplication<Application>().getSharedPreferences("doxon_settings", Context.MODE_PRIVATE)
        return prefs.getString("saved_input_prompt", "") ?: ""
    }

    fun clearDoxonCache() {
        val context = getApplication<Application>()
        viewModelScope.launch {
            try {
                val cacheDir = context.cacheDir
                if (cacheDir.exists() && cacheDir.isDirectory) {
                    cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                }
                context.externalCacheDir?.let { extCache ->
                    if (extCache.exists() && extCache.isDirectory) {
                        extCache.listFiles()?.forEach { it.deleteRecursively() }
                    }
                }
                SelfHealingMonitor.logEvent("Cache components forcefully purged by root request.")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        hapticHelper.triggerVibration()
    }

    private fun triggerAutoReconnectHandshake() {
        viewModelScope.launch {
            repository.resumePendingMessages { sessId, _ ->
                executeAIServicePipeline(sessId)
            }
        }
    }

    fun setIncognitoMode(active: Boolean) {
        _isIncognito.value = active
        _currentSession.value = null
        val prefs = getApplication<Application>().getSharedPreferences("doxon_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("saved_is_incognito", active)
            .putLong("saved_session_id", -1L)
            .apply()
        hapticHelper.triggerVibration()
        SelfHealingMonitor.logEvent("Incognito Isolation Fence state: $active")
    }

    fun selectSession(session: ChatSession?) {
        messagesCollectionJob?.cancel()
        _currentSession.value = session
        val prefs = getApplication<Application>().getSharedPreferences("doxon_settings", Context.MODE_PRIVATE)
        prefs.edit().putLong("saved_session_id", session?.id ?: -1L).apply()
        
        if (session != null) {
            messagesCollectionJob = viewModelScope.launch {
                repository.getMessages(session.id).collect { list ->
                    _messages.value = list
                    if (list.lastOrNull()?.role == "user" && !(list.lastOrNull()?.isPending ?: false) && !_isGenerating.value) {
                        executeAIServicePipeline(session.id)
                    }
                }
            }
        } else {
            _messages.value = emptyList()
        }
        hapticHelper.triggerVibration()
    }

    fun createNewChat(title: String, isIncog: Boolean = _isIncognito.value) {
        viewModelScope.launch {
            val sessionId = repository.createSession(title, isIncog)
            repository.getSessionById(sessionId)?.let {
                selectSession(it)
            }
        }
        hapticHelper.triggerVibration()
    }

    fun startNewSessionWithPrompt(prompt: String, isIncog: Boolean = _isIncognito.value) {
        if (!isNetworkAvailable()) {
            triggerNetworkToast()
            return
        }
        viewModelScope.launch {
            val title = if (prompt.length > 20) prompt.take(17) + "..." else prompt
            val sessionId = repository.createSession(title, isIncog)
            val session = repository.getSessionById(sessionId)
            if (session != null) {
                selectSession(session)
                repository.saveUserMessage(sessionId, prompt, null, null)
                hapticHelper.triggerVibration()
                executeAIServicePipeline(sessionId)
                if (isAutoSyncEnabled.value && !isIncog) {
                    triggerCloudSync()
                }
            }
        }
    }

    fun renameChat(sessionId: Long, newTitle: String) {
        viewModelScope.launch {
            repository.renameSession(sessionId, newTitle)
            if (_currentSession.value?.id == sessionId) {
                _currentSession.value = repository.getSessionById(sessionId)
            }
        }
        hapticHelper.triggerVibration()
    }

    fun deleteChat(sessionId: Long) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            if (_currentSession.value?.id == sessionId) {
                selectSession(null)
            }
        }
        hapticHelper.triggerVibration()
    }

    fun migrateIncognitoToNormal(sessionId: Long) {
        viewModelScope.launch {
            val newSessionId = repository.migrateIncognitoSession(sessionId)
            if (newSessionId != -1L) {
                _isIncognito.value = false
                repository.getSessionById(newSessionId)?.let {
                    selectSession(it)
                }
            }
        }
        hapticHelper.triggerVibration()
    }

    fun sendMessage(text: String, mediaUri: String? = null, mediaMimeType: String? = null) {
        if (!isNetworkAvailable()) {
            triggerNetworkToast()
            return
        }
        val session = _currentSession.value ?: return
        viewModelScope.launch {
            repository.saveUserMessage(session.id, text, mediaUri, mediaMimeType)
            hapticHelper.triggerVibration()
            executeAIServicePipeline(session.id)
            if (isAutoSyncEnabled.value && !session.isIncognito) {
                triggerCloudSync()
            }
        }
    }

    private fun executeAIServicePipeline(sessionId: Long) {
        activeGenerationJob?.cancel()
        activeGenerationJob = viewModelScope.launch {
            try {
                _isGenerating.value = true
                _isSearchingLive.value = _isSearchGroundingEnabled.value

                repository.getAIResponse(
                    sessionId = sessionId,
                    enableSearchGrounding = _isSearchGroundingEnabled.value,
                    onResponse = { _, _ ->
                        _isGenerating.value = false
                        _isSearchingLive.value = false
                        hapticHelper.triggerVibration()
                        if (isAutoSyncEnabled.value && !_isIncognito.value) {
                            triggerCloudSync()
                        }
                    },
                    onError = { _ ->
                        _isGenerating.value = false
                        _isSearchingLive.value = false
                    }
                )
            } catch (e: Exception) {
                _isGenerating.value = false
                _isSearchingLive.value = false
            }
        }
    }

    fun stopActiveResponse() {
        activeGenerationJob?.cancel()
        _isGenerating.value = false
        val session = _currentSession.value
        if (session != null) {
            viewModelScope.launch {
                repository.insertMessageDirectly(
                    session.id,
                    ChatMessage(
                        sessionId = session.id,
                        role = "model",
                        text = "Active response interrupted by user."
                    )
                )
                hapticHelper.triggerVibration()
            }
        }
    }

    fun editPromptAndReProcess(messageId: Long, newText: String) {
        val session = _currentSession.value ?: return
        viewModelScope.launch {
            repository.deleteSubsequentAnswersAndSaveEdit(session.id, messageId, newText)
            hapticHelper.triggerVibration()
            executeAIServicePipeline(session.id)
        }
    }

    fun clearChatHistory(sessionId: Long) {
        viewModelScope.launch {
            repository.clearSessionChat(sessionId)
        }
        hapticHelper.triggerVibration()
    }

    fun dismissTab(tabName: String) {
        _dismissedTabs.value = _dismissedTabs.value + tabName
        _lastDismissedTab.value = tabName
        hapticHelper.triggerVibration()
    }

    fun undoDismissTab() {
        _lastDismissedTab.value?.let { tab ->
            _dismissedTabs.value = _dismissedTabs.value - tab
            _lastDismissedTab.value = null
            hapticHelper.triggerVibration()
        }
    }

    fun selectTab(tabName: String?) {
        _activeTab.value = tabName
        hapticHelper.triggerVibration()
    }

    fun setThemeMode(mode: String) {
        _themeMode.value = mode
        getApplication<Application>().getSharedPreferences("doxon_settings", Context.MODE_PRIVATE)
            .edit().putString("theme_mode", mode).apply()
        hapticHelper.triggerVibration()
    }

    // Cloud Synchronisation Hub handlers
    fun registerAndLogin(email: String, passwordRaw: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            val success = cloudSyncManager.registerNewAccountInCloud(email, passwordRaw)
            if (success) {
                val pwhash = cloudSyncManager.hashPassword(passwordRaw)
                cloudSyncManager.login(email, "Doxon_Secure_Cloud_Token_${System.currentTimeMillis()}", pwhash)
                _authenticatedEmail.value = email
                _profileDisplayName.value = email.substringBefore("@")
                _profilePicture.value = "avatar_default"
                _lastSyncedTime.value = cloudSyncManager.getLastSyncedTime()
                hapticHelper.triggerVibration()
                onSuccess()
            } else {
                onError("Account with this email already exists.")
            }
        }
    }

    fun loginSyncAccount(email: String, passwordRaw: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            val verified = cloudSyncManager.verifyCloudLogin(email, passwordRaw)
            if (verified) {
                val pwhash = cloudSyncManager.hashPassword(passwordRaw)
                cloudSyncManager.login(email, "Doxon_Secure_Cloud_Token_${System.currentTimeMillis()}", pwhash)
                _authenticatedEmail.value = email
                _profileDisplayName.value = cloudSyncManager.getDisplayName() ?: email.substringBefore("@")
                _profilePicture.value = cloudSyncManager.getProfilePicture() ?: "avatar_default"
                _lastSyncedTime.value = cloudSyncManager.getLastSyncedTime()
                
                // When logging in, automatic secure restore and decryption
                triggerCloudRestore {
                    hapticHelper.triggerVibration()
                    onSuccess()
                }
            } else {
                onError("Invalid credentials password match.")
            }
        }
    }

    fun logoutSyncAccount() {
        cloudSyncManager.logout()
        _authenticatedEmail.value = null
        _profileDisplayName.value = "User"
        _profilePicture.value = "avatar_default"
        _lastSyncedTime.value = 0L
        hapticHelper.triggerVibration()
    }

    fun triggerCloudSync(onComplete: (com.example.data.sync.SyncResult) -> Unit = {}) {
        viewModelScope.launch {
            if (_isSyncing.value) return@launch
            _isSyncing.value = true
            val result = cloudSyncManager.performCloudBackup()
            _isSyncing.value = false
            if (result is com.example.data.sync.SyncResult.Success) {
                _lastSyncedTime.value = result.timestamp
                SelfHealingMonitor.logEvent("Cloud Database synchronization transaction accomplished successfully.")
            } else if (result is com.example.data.sync.SyncResult.Failure) {
                SelfHealingMonitor.logEvent("EXCEPTION: Cryptographic cloud interface fault: ${result.message}")
            }
            hapticHelper.triggerVibration()
            onComplete(result)
        }
    }

    fun triggerCloudRestore(onComplete: (com.example.data.sync.SyncResult) -> Unit = {}) {
        viewModelScope.launch {
            if (_isSyncing.value) return@launch
            _isSyncing.value = true
            val result = cloudSyncManager.performCloudRestore()
            _isSyncing.value = false
            if (result is com.example.data.sync.SyncResult.Success) {
                _lastSyncedTime.value = result.timestamp
                _profileDisplayName.value = cloudSyncManager.getDisplayName() ?: (cloudSyncManager.getAuthenticatedEmail()?.substringBefore("@") ?: "User")
                _profilePicture.value = cloudSyncManager.getProfilePicture() ?: "avatar_default"
                val prefs = getApplication<Application>().getSharedPreferences("doxon_settings", Context.MODE_PRIVATE)
                _themeMode.value = prefs.getString("theme_mode", "system") ?: "system"
                _isSearchGroundingEnabled.value = prefs.getBoolean("search_grounding_enabled", true)
                _isLowLatency.value = prefs.getBoolean("is_low_latency", false)
                _isHighThinking.value = prefs.getBoolean("is_high_thinking", false)
                _isMapsGroundingEnabled.value = prefs.getBoolean("is_maps_grounding", false)
                _aiResponseLength.value = prefs.getString("ai_response_length", "balanced") ?: "balanced"
                _aiCreativity.value = prefs.getString("ai_creativity", "balanced") ?: "balanced"
                _customAiPersona.value = prefs.getString("custom_ai_persona", "") ?: ""
                _isAutoSyncEnabled.value = cloudSyncManager.isAutoSyncEnabled()
                SelfHealingMonitor.logEvent("Cloud Database synchronization payload downloaded and merged.")
            }
            hapticHelper.triggerVibration()
            onComplete(result)
        }
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        cloudSyncManager.setAutoSyncEnabled(enabled)
        _isAutoSyncEnabled.value = enabled
        hapticHelper.triggerVibration()
    }
}
