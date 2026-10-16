package com.aesprt.aquahub.domain.model

enum class ExpenseCategory(val displayName: String) {
    ELECTRICITY("Electricity"),
    WATER("Water supply"),
    FUEL("Fuel & delivery"),
    SUPPLIES("Supplies"),
    SALARIES("Salaries & wages"),
    MAINTENANCE("Maintenance"),
    RENT("Rent"),
    PERMITS("Permits & fees"),
    OTHER("Other")
}

enum class ExpensePaymentMethod(val displayName: String) {
    CASH("Cash"),
    GCASH("GCash"),
    MAYA("Maya"),
    BANK_TRANSFER("Bank transfer"),
    OTHER("Other")
}

data class Expense(
    val id: Long = 0,
    val stationId: Long,
    val amountCentavos: Long,
    val category: ExpenseCategory,
    val description: String,
    val vendor: String? = null,
    val paymentMethod: ExpensePaymentMethod = ExpensePaymentMethod.CASH,
    val referenceNumber: String? = null,
    val notes: String? = null,
    val incurredAt: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true
)

