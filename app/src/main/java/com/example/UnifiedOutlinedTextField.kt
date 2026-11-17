package com.example

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.example.ui.components.UnifiedTextField

/**
 * Keeps the existing screen declarations concise while routing their editable fields through the
 * shared RTL input design. Read-only dropdown anchors retain Material styling and semantics.
 */
@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = false,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val numeric = keyboardOptions.keyboardType == KeyboardType.Number ||
        keyboardOptions.keyboardType == KeyboardType.Decimal
    val decimal = keyboardOptions.keyboardType == KeyboardType.Decimal

    UnifiedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label?.let { labelContent -> "" },
        modifier = modifier,
        numeric = numeric && !readOnly,
        decimal = decimal,
        keyboardOptions = keyboardOptions,
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        labelContent = label ?: { Text("") },
        trailingIcon = trailingIcon,
    )
}
