package com.bedrosn.fcmnotifier

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.*

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "fcm_notifications")

@Serializable
data class NotificationData(
    val title: String,
    val body: String,
    val timestamp: String
)

class NotificationViewModel(private val context: Context) : ViewModel() {
    private val _fcmToken = MutableStateFlow("")
    val fcmToken: StateFlow<String> = _fcmToken.asStateFlow()

    private val _notifications = MutableStateFlow<List<NotificationData>>(emptyList())
    val notifications: StateFlow<List<NotificationData>> = _notifications.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val NOTIFICATIONS_KEY = stringPreferencesKey("notifications_list")

        // Singleton instance for cross-activity access (from FCMService)
        private var instance: NotificationViewModel? = null

        fun getInstance(context: Context? = null): NotificationViewModel {
            if (instance == null && context != null) {
                instance = NotificationViewModel(context.applicationContext)
                // Load persisted notifications on first access
                instance?.loadNotifications()
            }
            return instance ?: throw IllegalStateException("NotificationViewModel not initialized with context")
        }

        fun setInstance(viewModel: NotificationViewModel) {
            instance = viewModel
        }
    }

    init {
        // Load persisted notifications when ViewModel is created
        loadNotifications()
    }

    fun updateToken(token: String) {
        _fcmToken.value = token
    }

    fun addNotification(title: String, body: String) {
        viewModelScope.launch {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date())
            val notification = NotificationData(title, body, timestamp)

            // Add to in-memory list
            _notifications.value = listOf(notification) + _notifications.value

            // Persist to DataStore
            saveNotifications()
        }
    }

    fun clearNotifications() {
        viewModelScope.launch {
            _notifications.value = emptyList()

            // Clear from DataStore
            context.dataStore.edit { preferences ->
                preferences.remove(NOTIFICATIONS_KEY)
            }
        }
    }

    private fun loadNotifications() {
        viewModelScope.launch {
            try {
                val notificationsJson = context.dataStore.data.map { preferences ->
                    preferences[NOTIFICATIONS_KEY] ?: "[]"
                }.first()

                val loadedNotifications = json.decodeFromString<List<NotificationData>>(notificationsJson)
                _notifications.value = loadedNotifications
            } catch (e: Exception) {
                // If loading fails, start with empty list
                _notifications.value = emptyList()
            }
        }
    }

    private suspend fun saveNotifications() {
        try {
            val notificationsJson = json.encodeToString(_notifications.value)
            context.dataStore.edit { preferences ->
                preferences[NOTIFICATIONS_KEY] = notificationsJson
            }
        } catch (e: Exception) {
            // Log error but don't crash
            android.util.Log.e("NotificationViewModel", "Error saving notifications: ${e.message}")
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return NotificationViewModel(context) as T
        }
    }
}
