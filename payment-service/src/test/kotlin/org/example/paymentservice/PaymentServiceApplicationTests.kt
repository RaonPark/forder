package org.example.paymentservice

import org.example.paymentservice.service.NoOpTransactionConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@Import(TestcontainersConfiguration::class, NoOpTransactionConfig::class)
@SpringBootTest(properties = ["spring.kafka.listener.auto-startup=false"])
class PaymentServiceApplicationTests {

    @Test
    fun contextLoads() {
    }

}
