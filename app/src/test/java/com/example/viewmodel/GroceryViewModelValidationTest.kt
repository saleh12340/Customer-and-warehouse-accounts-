package com.example.viewmodel

import com.example.data.InvoiceItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryViewModelValidationTest {
  private fun line(quantity: Int = 2, unitPrice: Double = 5.0, totalPrice: Double = 10.0) =
    InvoiceItemEntity(
      invoiceId = 0L,
      itemId = 1L,
      itemName = "ماء",
      quantity = quantity,
      unitPrice = unitPrice,
      totalPrice = totalPrice
    )

  @Test
  fun validPartialInvoiceReturnsNormalizedTotalsAndStatus() {
    val result = GroceryViewModel.validateInvoiceInput(" INV-1 ", " cash ", listOf(line()), 4.0)

    assertTrue(result.isValid)
    assertEquals("CASH", result.normalizedType)
    assertEquals(10.0, result.totalAmount, 0.000001)
    assertEquals(6.0, result.remainingAmount, 0.000001)
    assertEquals("PARTIAL", result.status)
  }

  @Test
  fun rejectsBlankInvoiceNumberAndEmptyCart() {
    assertFalse(GroceryViewModel.validateInvoiceInput("  ", "CASH", listOf(line()), 0.0).isValid)
    assertFalse(GroceryViewModel.validateInvoiceInput("INV-1", "CASH", emptyList(), 0.0).isValid)
  }

  @Test
  fun rejectsInvalidQuantitiesAndPrices() {
    assertFalse(GroceryViewModel.validateInvoiceInput("INV-1", "CASH", listOf(line(quantity = 0, totalPrice = 0.0)), 0.0).isValid)
    assertFalse(GroceryViewModel.validateInvoiceInput("INV-1", "CASH", listOf(line(unitPrice = -1.0, totalPrice = -2.0)), 0.0).isValid)
  }

  @Test
  fun rejectsLineTotalThatDoesNotMatchQuantityTimesPrice() {
    val result = GroceryViewModel.validateInvoiceInput(
      "INV-1",
      "CASH",
      listOf(line(quantity = 2, unitPrice = 5.0, totalPrice = 9.0)),
      0.0
    )

    assertFalse(result.isValid)
    assertTrue(result.message.isNotBlank())
  }

  @Test
  fun rejectsPaymentOutsideInvoiceTotal() {
    assertFalse(GroceryViewModel.validateInvoiceInput("INV-1", "CASH", listOf(line()), -1.0).isValid)
    assertFalse(GroceryViewModel.validateInvoiceInput("INV-1", "CASH", listOf(line()), 11.0).isValid)
  }

  @Test
  fun fullyPaidInvoiceIsMarkedPaid() {
    val result = GroceryViewModel.validateInvoiceInput("INV-1", "SALE", listOf(line()), 10.0)

    assertTrue(result.isValid)
    assertEquals("PAID", result.status)
    assertEquals(0.0, result.remainingAmount, 0.000001)
  }

  @Test
  fun allowsCustomUnlistedItemAndRejectsNegativeItemId() {
    val customLine = InvoiceItemEntity(
      invoiceId = 0L,
      itemId = 0L,
      itemName = "صنف حر غير مسجل بالمخزن",
      quantity = 3,
      unitPrice = 250.0,
      totalPrice = 750.0
    )
    val customRes = GroceryViewModel.validateInvoiceInput("INV-FREE", "CASH", listOf(customLine), 750.0)
    assertTrue(customRes.isValid)
    assertEquals(750.0, customRes.totalAmount, 0.000001)

    val invalidLine = customLine.copy(itemId = -1L)
    assertFalse(GroceryViewModel.validateInvoiceInput("INV-NEG", "CASH", listOf(invalidLine), 0.0).isValid)
  }
}
