package com.example.llama.component

import android.app.DownloadManager
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.llama.MainViewModel
import com.example.llama.model.Downloadable
import com.example.llama.model.SettingsMenuItem
import com.example.llama.ProfileSettings
import com.example.llama.ModelSettings
import com.example.llama.NotificationsSettings
import com.example.llama.RecentChatsSettings
import com.example.llama.AboutSettings
import com.example.llama.ArchivedChatsSettings
import com.example.llama.CallsSettings
import com.example.llama.GroupsSettings
import com.example.llama.PrivacySettings

@Composable
fun SettingsContent(
    selectedItem: SettingsMenuItem,
    viewModel: MainViewModel,
    downloadManager: DownloadManager,
    modelOptions: List<Downloadable>,
    prefs: SharedPreferences,
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (selectedItem) {
        SettingsMenuItem.PROFILE -> ProfileSettings(
            viewModel = viewModel,
            prefs = prefs,
            modifier = modifier
        )
        SettingsMenuItem.NOTIFICATIONS -> NotificationsSettings(modifier = modifier)
        SettingsMenuItem.RECENT_CHATS -> RecentChatsSettings(modifier = modifier)
        SettingsMenuItem.ABOUT -> AboutSettings(modifier = modifier)
        SettingsMenuItem.ARCHIVED_CHATS -> ArchivedChatsSettings(modifier = modifier)
        SettingsMenuItem.CALLS -> CallsSettings(modifier = modifier)
        SettingsMenuItem.GROUPS -> GroupsSettings(modifier = modifier)
        SettingsMenuItem.PRIVACY -> PrivacySettings(modifier = modifier)
        SettingsMenuItem.MODEL -> ModelSettings(
            viewModel = viewModel,
            downloadManager = downloadManager,
            modelOptions = modelOptions,
            onImportClick = onImportClick,
            prefs = prefs,
            modifier = modifier
        )
    }
}
