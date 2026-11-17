package com.example.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedTextFieldTest {
    @Test
    fun integerInputKeepsDigitsAndDropsSignsAndSeparators() {
        assertEquals("1204", sanitizeNumericInput("-12.0a4", allowDecimal = false))
    }

    @Test
    fun decimalInputNormalizesArabicDigitsAndAllowsOneSeparator() {
        assertEquals("12.345", sanitizeNumericInput("١٢٫٣٤.٥", allowDecimal = true))
    }

    @Test
    fun decimalInputDropsNegativeSigns() {
        assertEquals("5.25", sanitizeNumericInput("-5.25", allowDecimal = true))
    }

    @Test
    fun emptyAndInvalidNumericInputCanBeCleared() {
        assertEquals("", sanitizeNumericInput("-+abc", allowDecimal = true))
    }

    @Test
    fun decimalSeparatorIsNotDuplicated() {
        assertEquals("1.234", sanitizeNumericInput("1..2.3.4", allowDecimal = true))
    }
}
