package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun UnifiedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String? = null,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    decimal: Boolean = false,
    keyboardOptions: KeyboardOptions? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    labelContent: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }

    LaunchedEffect(value) {
        if (fieldValue.text != value) fieldValue = TextFieldValue(value, selection = TextRange(value.length))
    }

    val inputModifier = if (numeric && !readOnly) {
        modifier.onFocusChanged { focusState ->
            if (focusState.isFocused) fieldValue = fieldValue.copy(selection = TextRange(0, fieldValue.text.length))
        }
    } else modifier

    OutlinedTextField(
        value = fieldValue,
        onValueChange = { proposed ->
            val accepted = if (numeric && !readOnly) sanitizeNumericInput(proposed.text, decimal) else proposed.text
            val start = proposed.selection.start.coerceIn(0, accepted.length)
            val end = proposed.selection.end.coerceIn(0, accepted.length)
            fieldValue = TextFieldValue(accepted, selection = TextRange(start, end))
            onValueChange(accepted)
        },
        modifier = inputModifier.fillMaxWidth(),
        label = labelContent ?: label?.let { text -> { Text(text) } },
        keyboardOptions = keyboardOptions ?: KeyboardOptions(
            keyboardType = when {
                !numeric -> KeyboardType.Text
                decimal -> KeyboardType.Decimal
                else -> KeyboardType.Number
            }
        ),
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        trailingIcon = trailingIcon,
    )
}

@Composable
fun MoneyTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    UnifiedTextField(value, onValueChange, label, modifier, numeric = true, decimal = true, enabled = enabled)
}

@Composable
fun QuantityTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    UnifiedTextField(value, onValueChange, label, modifier, numeric = true, enabled = enabled)
}

@Composable
fun ResponsiveFieldRow(modifier: Modifier = Modifier, breakpoint: Dp = 520.dp, content: @Composable (Modifier, Modifier) -> Unit) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (maxWidth >= breakpoint) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                content(Modifier.weight(1f), Modifier.weight(1f))
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content(Modifier.fillMaxWidth(), Modifier.fillMaxWidth())
            }
        }
    }
}

internal fun sanitizeNumericInput(input: String, allowDecimal: Boolean): String {
    val normalizedDigits = input.map { character ->
        when (character) {
            in '\u0660'..'\u0669' -> ('0'.code + (character.code - '\u0660'.code)).toChar()
            in '\u06F0'..'\u06F9' -> ('0'.code + (character.code - '\u06F0'.code)).toChar()
            else -> character
        }
    }.joinToString(separator = "")

    val result = StringBuilder(normalizedDigits.length)
    var hasDecimalPoint = false
    normalizedDigits.forEach { character ->
        val digit = character.digitToIntOrNull()
        when {
            digit != null -> result.append(digit)
            allowDecimal && (character == '.' || character == '\u066B') && !hasDecimalPoint -> {
                result.append('.')
                hasDecimalPoint = true
            }
        }
    }
    return result.toString()
}
