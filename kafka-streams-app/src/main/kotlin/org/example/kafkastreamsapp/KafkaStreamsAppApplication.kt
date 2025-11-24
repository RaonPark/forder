package org.example.kafkastreamsapp

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class KafkaStreamsAppApplication

fun main(args: Array<String>) {
    runApplication<KafkaStreamsAppApplication>(*args)
}
