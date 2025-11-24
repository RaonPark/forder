package org.example.orderservice

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
    fromApplication<OrderServiceApplication>().with(TestcontainersConfiguration::class).run(*args)
}
