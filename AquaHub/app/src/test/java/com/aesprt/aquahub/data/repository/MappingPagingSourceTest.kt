package com.aesprt.aquahub.data.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingPagingSourceTest {

    @Test
    fun `load maps one requested page and preserves page metadata`() = runTest {
        val source = RecordingPagingSource((1..50).toList())
        val mapped = MappingPagingSource(source) { "item-$it" }

        val result = mapped.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 20,
                placeholdersEnabled = false
            )
        ) as PagingSource.LoadResult.Page

        assertEquals(20, source.lastRequestedSize)
        assertEquals((1..20).map { "item-$it" }, result.data)
        assertEquals(20, result.nextKey)
        assertEquals(0, result.itemsBefore)
        assertEquals(30, result.itemsAfter)
    }

    @Test
    fun `room source invalidation propagates to mapped source`() {
        val source = RecordingPagingSource((1..5).toList())
        val mapped = MappingPagingSource(source) { it.toString() }

        source.invalidate()

        assertTrue(mapped.invalid)
    }

    private class RecordingPagingSource(
        private val values: List<Int>
    ) : PagingSource<Int, Int>() {
        var lastRequestedSize: Int = 0

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Int> {
            lastRequestedSize = params.loadSize
            val start = params.key ?: 0
            val end = (start + params.loadSize).coerceAtMost(values.size)
            return LoadResult.Page(
                data = values.subList(start, end),
                prevKey = if (start == 0) null else (start - params.loadSize).coerceAtLeast(0),
                nextKey = if (end == values.size) null else end,
                itemsBefore = start,
                itemsAfter = values.size - end
            )
        }

        override fun getRefreshKey(state: PagingState<Int, Int>): Int? = state.anchorPosition
    }
}
