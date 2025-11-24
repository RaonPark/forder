package org.example.productservice

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
    fromApplication<ProductServiceApplication>().with(TestcontainersConfiguration::class).run(*args)
}
