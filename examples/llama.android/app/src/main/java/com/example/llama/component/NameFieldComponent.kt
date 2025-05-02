package com.example.llama.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.Color
import com.example.llama.ui.theme.PurpleLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NameFieldComponent(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    isEditing: Boolean,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var internalIsEditing by remember { mutableStateOf(isEditing) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = PurpleLabel, fontSize = 14.sp) },
        placeholder = { Text(if (label == "Your Name") "User" else "Jarvis", color = Color.Gray.copy(alpha = 0.5f)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = label,
                tint = PurpleLabel
            )
        },
        trailingIcon = {
            if (internalIsEditing) {
                IconButton(onClick = {
                    internalIsEditing = false
                    onSave()
                }) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Save",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                IconButton(onClick = { internalIsEditing = true }) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Edit",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                onFocusChange(focusState.isFocused)
            },
        readOnly = !internalIsEditing,
        colors = TextFieldDefaults.outlinedTextFieldColors(
            focusedBorderColor = PurpleLabel,
            unfocusedBorderColor = PurpleLabel.copy(alpha = 0.5f),
            focusedLabelColor = PurpleLabel,
            unfocusedLabelColor = PurpleLabel.copy(alpha = 0.5f),
            cursorColor = PurpleLabel
        )
    )
}
