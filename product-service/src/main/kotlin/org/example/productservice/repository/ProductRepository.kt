package org.example.productservice.repository

import common.document.ProductStatus
import kotlinx.coroutines.flow.Flow
import org.example.productservice.document.Products
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.kotlin.CoroutineSortingRepository

interface ProductRepository :
    CoroutineCrudRepository<Products, String>,
    CoroutineSortingRepository<Products, String> {

    fun findBySellerIdOrderByCreatedAtDesc(sellerId: String): Flow<Products>

    fun findBySellerIdAndStatusOrderByCreatedAtDesc(sellerId: String, status: ProductStatus): Flow<Products>

    fun findByCategoryOrderByCreatedAtDesc(category: String): Flow<Products>
}
