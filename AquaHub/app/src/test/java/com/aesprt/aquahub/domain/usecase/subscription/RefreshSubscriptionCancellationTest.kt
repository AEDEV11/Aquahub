package com.aesprt.aquahub.domain.usecase.subscription

import com.aesprt.aquahub.domain.model.Subscription
import com.aesprt.aquahub.domain.repository.SubscriptionRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class RefreshSubscriptionCancellationTest {
    @Test fun `cancelled remote refresh does not continue changing cached access`() = runTest {
        val cancellation = CancellationException("screen was closed")
        var expirationCalls = 0
        val unused = Proxy.newProxyInstance(
            SubscriptionRepository::class.java.classLoader,
            arrayOf(SubscriptionRepository::class.java),
        ) { _, method, _ -> error("Unexpected call: ${method.name}") } as SubscriptionRepository
        val repository = object : SubscriptionRepository by unused {
            override suspend fun refreshFromSupabase(): Boolean = throw cancellation
            override suspend fun processSubscriptionExpiration(stationId: Long): Subscription? {
                expirationCalls++
                return null
            }
        }
        try {
            RefreshSubscriptionStateUseCase(repository)(1L)
            fail("Cancellation must propagate")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
        assertEquals(0, expirationCalls)
    }
}
