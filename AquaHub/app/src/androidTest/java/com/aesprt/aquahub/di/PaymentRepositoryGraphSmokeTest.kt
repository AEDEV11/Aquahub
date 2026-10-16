package com.aesprt.aquahub.di

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aesprt.aquahub.domain.usecase.order.CreateWalkInOrderUseCase
import com.aesprt.aquahub.domain.repository.PaymentRepository
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class PaymentRepositoryGraphSmokeTest {
    @Test
    fun paymentRepositoryResolvesFromApplicationGraph() {
        val repository = GlobalContext.get().get<PaymentRepository>()

        assertNotNull(repository)
    }

    @Test
    fun walkInOrderUseCaseResolvesFromApplicationGraph() {
        val useCase = GlobalContext.get().get<CreateWalkInOrderUseCase>()

        assertNotNull(useCase)
    }
}
