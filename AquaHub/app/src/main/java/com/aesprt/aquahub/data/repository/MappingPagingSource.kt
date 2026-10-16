package com.aesprt.aquahub.data.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState

/** Keeps Room invalidation while translating persistence models at the repository boundary. */
internal class MappingPagingSource<Source : Any, Target : Any>(
    private val source: PagingSource<Int, Source>,
    private val transform: (Source) -> Target
) : PagingSource<Int, Target>() {

    init {
        source.registerInvalidatedCallback(::invalidate)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Target> =
        when (val result = source.load(params)) {
            is LoadResult.Page -> LoadResult.Page(
                data = result.data.map(transform),
                prevKey = result.prevKey,
                nextKey = result.nextKey,
                itemsBefore = result.itemsBefore,
                itemsAfter = result.itemsAfter
            )
            is LoadResult.Error -> LoadResult.Error(result.throwable)
            is LoadResult.Invalid -> LoadResult.Invalid()
        }

    override fun getRefreshKey(state: PagingState<Int, Target>): Int? {
        val anchorPosition = state.anchorPosition ?: return null
        val anchorPage = state.closestPageToPosition(anchorPosition) ?: return null
        return anchorPage.prevKey?.let { it + state.config.pageSize }
            ?: anchorPage.nextKey?.let { it - state.config.pageSize }
    }
}
