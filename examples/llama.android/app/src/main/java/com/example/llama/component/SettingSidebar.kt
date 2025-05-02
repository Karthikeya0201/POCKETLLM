package com.example.llama.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.llama.model.SettingsMenuItem
import com.example.llama.ui.theme.PurpleDark
import com.example.llama.ui.theme.PurpleLight

@Composable
fun SettingsSidebar(
    selectedItem: SettingsMenuItem,
    onItemClick: (SettingsMenuItem) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .width(200.dp)
            .background(PurpleDark)
    ) {
        items(SettingsMenuItem.values()) { item ->
            SettingsSidebarItem(
                item = item,
                isSelected = item == selectedItem,
                onClick = { onItemClick(item) }
            )
        }
    }
}

@Composable
fun SettingsSidebarItem(
    item: SettingsMenuItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isSelected) PurpleLight else Color.Transparent
    val textColor = if (isSelected) Color.Black else Color.White

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(backgroundColor)
            .padding(16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = item.title,
            color = textColor,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
