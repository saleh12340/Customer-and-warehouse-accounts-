package com.example

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.example.ui.components.UnifiedTextField

/** Routes screen fields to the unified full-width Material 3 input while retaining existing call sites. */
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
    UnifiedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        numeric = numeric,
        decimal = keyboardOptions.keyboardType == KeyboardType.Decimal,
        keyboardOptions = keyboardOptions,
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        labelContent = label,
        trailingIcon = trailingIcon,
    )
}
