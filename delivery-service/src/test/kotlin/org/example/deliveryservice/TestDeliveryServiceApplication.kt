package org.example.deliveryservice

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
    fromApplication<DeliveryServiceApplication>().with(TestcontainersConfiguration::class).run(*args)
}
