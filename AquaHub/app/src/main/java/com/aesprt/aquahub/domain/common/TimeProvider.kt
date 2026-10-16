package com.aesprt.aquahub.domain.common

interface TimeProvider {
    fun now(): Long
}

class SystemTimeProvider : TimeProvider {
    override fun now(): Long = System.currentTimeMillis()
}
