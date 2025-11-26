package org.example.queryservice.document

import common.document.payment.PaymentMethod
import common.document.payment.PaymentStatus
import common.document.payment.PgProvider
import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.annotations.Field
import org.springframework.data.elasticsearch.annotations.FieldType
import java.math.BigDecimal
import java.time.Instant

@Document(indexName = "payments-view")
data class EnrichedPayment(
    @Id
    val paymentId: String,

    @Field(type = FieldType.Keyword)
    val orderId: String,

    @Field(type = FieldType.Keyword)
    val userId: String,

    val totalAmount: BigDecimal,
    val canceledAmount: BigDecimal,

    @Field(type = FieldType.Keyword)
    val status: PaymentStatus,

    @Field(type = FieldType.Keyword)
    val method: PaymentMethod,

    @Field(type = FieldType.Keyword)
    val pgTid: String?,

    @Field(type = FieldType.Keyword)
    val pgProvider: PgProvider,

    val createdAt: Instant,
    val updatedAt: Instant,
    val canceledAt: Instant?
)