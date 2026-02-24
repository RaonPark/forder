package org.example.orderservice.service

import common.document.order.OrderStatus
import org.example.orderservice.document.OrderItem
import org.example.orderservice.document.Orders
import org.example.orderservice.dto.CreateOrderRequest
import org.example.orderservice.dto.CreateOrderResponse
import org.example.orderservice.repository.OrderRepository
import org.example.orderservice.saga.OrderSagaOrchestrator
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val orderSagaOrchestrator: OrderSagaOrchestrator
) {

    suspend fun createOrder(request: CreateOrderRequest): CreateOrderResponse {
        val order = Orders(
            orderId = UUID.randomUUID().toString(),
            userId = request.userId,
            status = OrderStatus.PENDING,
            items = request.items.map {
                OrderItem(
                    productId = it.productId,
                    quantity = it.quantity,
                    priceAtOrder = it.priceAtOrder
                )
            },
            totalAmount = request.items.sumOf { it.priceAtOrder * it.quantity.toBigDecimal() },
            deliveryAddress = request.deliveryAddress,
            receiverName = request.receiverName,
            receiverPhone = request.receiverPhone
        )

        val saved = orderRepository.save(order)
        orderSagaOrchestrator.startSaga(saved)

        return CreateOrderResponse(
            orderId = saved.orderId,
            totalAmount = saved.totalAmount,
            createdAt = saved.createdAt
        )
    }
}
