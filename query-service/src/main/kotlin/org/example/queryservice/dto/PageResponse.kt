package org.example.queryservice.dto

import kotlin.math.ceil

data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
) {
    companion object {
        fun <T> of(content: List<T>, page: Int, size: Int, totalElements: Long): PageResponse<T> =
            PageResponse(
                content      = content,
                page         = page,
                size         = size,
                totalElements = totalElements,
                totalPages   = ceil(totalElements.toDouble() / size).toInt().coerceAtLeast(1)
            )
    }
}
