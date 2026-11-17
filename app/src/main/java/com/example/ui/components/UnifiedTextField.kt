package com.example.ui.components

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
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.layout.fillMaxWidth

/**
 * Shared, full-width input for Arabic Compose screens. Set [numeric] for numeric values and
 * [decimal] when one decimal separator is allowed. Numeric fields select their contents on focus.
 */
@Composable
fun UnifiedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    decimal: Boolean = false,
    keyboardOptions: KeyboardOptions? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }

    LaunchedEffect(value) {
        if (fieldValue.text != value) {
            fieldValue = TextFieldValue(value, selection = TextRange(value.length))
        }
    }

    val inputModifier = if (numeric) {
        modifier.onFocusChanged { focusState ->
            if (focusState.isFocused) {
                fieldValue = fieldValue.copy(selection = TextRange(0, fieldValue.text.length))
            }
        }
    } else {
        modifier
    }

    OutlinedTextField(
        value = fieldValue,
        onValueChange = { proposed ->
            val accepted = if (numeric) sanitizeNumericInput(proposed.text, decimal) else proposed.text
            fieldValue = TextFieldValue(accepted, selection = TextRange(accepted.length))
            onValueChange(accepted)
        },
        modifier = inputModifier.fillMaxWidth(),
        label = { Text(label) },
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
    )
}

/** Keeps digits and, for decimal fields, at most one decimal point; negative signs are discarded. */
internal fun sanitizeNumericInput(input: String, allowDecimal: Boolean): String {
    val result = StringBuilder(input.length)
    var hasDecimalPoint = false
    input.forEach { character ->
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
