package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.weight
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared, full-width input for Arabic Compose screens. Numeric inputs select their contents on focus. */
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
        if (fieldValue.text != value) {
            fieldValue = TextFieldValue(value, selection = TextRange(value.length))
        }
    }

    val inputModifier = if (numeric && !readOnly) {
        modifier.onFocusChanged { focusState ->
            if (focusState.isFocused) {
                fieldValue = fieldValue.copy(selection = TextRange(0, fieldValue.text.length))
            }
        }
    } else modifier

    OutlinedTextField(
        value = fieldValue,
        onValueChange = { proposed ->
            val accepted = if (numeric && !readOnly) sanitizeNumericInput(proposed.text, decimal) else proposed.text
            fieldValue = TextFieldValue(accepted, selection = TextRange(accepted.length))
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

/** A decimal amount/price field, with a decimal keypad and strict single-separator input. */
@Composable
fun MoneyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    UnifiedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        numeric = true,
        decimal = true,
        enabled = enabled,
    )
}

/** An integer quantity field; values below zero and non-digits are rejected. */
@Composable
fun QuantityTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    UnifiedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        numeric = true,
        decimal = false,
        enabled = enabled,
    )
}

/** Arranges paired form controls side-by-side on wider displays and stacks them on narrow screens. */
@Composable
fun ResponsiveFieldRow(
    modifier: Modifier = Modifier,
    breakpoint: Dp = 520.dp,
    content: @Composable (Modifier, Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (maxWidth >= breakpoint) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content(Modifier.weight(1f), Modifier.weight(1f))
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content(Modifier.fillMaxWidth(), Modifier.fillMaxWidth())
            }
        }
    }
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
