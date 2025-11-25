package org.example.orderservice.repository

import org.example.orderservice.document.Orders
import org.springframework.data.repository.kotlin.CoroutineSortingRepository

interface OrderRepository: CoroutineSortingRepository<Orders, String> {
}