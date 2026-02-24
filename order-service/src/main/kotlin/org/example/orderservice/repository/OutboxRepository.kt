package org.example.orderservice.repository

import org.example.orderservice.document.OutboxEvent
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface OutboxRepository : CoroutineCrudRepository<OutboxEvent, String>
