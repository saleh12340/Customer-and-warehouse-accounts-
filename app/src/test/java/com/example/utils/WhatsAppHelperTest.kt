package com.example.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class WhatsAppHelperTest {

  @Test
  fun normalizesLocalAndInternationalYemeniNumbers() {
    assertEquals("+967771234567", WhatsAppHelper.normalizeYemeniPhone("771234567"))
    assertEquals("+967771234567", WhatsAppHelper.normalizeYemeniPhone("0771234567"))
    assertEquals("+967771234567", WhatsAppHelper.normalizeYemeniPhone("077-123-4567"))
    assertEquals("+967771234567", WhatsAppHelper.normalizeYemeniPhone("967771234567"))
    assertEquals("+967771234567", WhatsAppHelper.normalizeYemeniPhone("00967771234567"))
    assertEquals("+967771234567", WhatsAppHelper.normalizeYemeniPhone("+967 771 234 567"))
  }

  @Test
  fun preservesNonYemeniInternationalNumbersAndBlank() {
    assertEquals("+966501234567", WhatsAppHelper.normalizeYemeniPhone("+966501234567"))
    assertEquals("+966501234567", WhatsAppHelper.normalizeYemeniPhone("00966501234567"))
    assertEquals("", WhatsAppHelper.normalizeYemeniPhone("   "))
  }
}
