package cl.duoc.pedidos360.ms_pago.listener;

import cl.duoc.pedidos360.ms_pago.config.KafkaTopics;
import cl.duoc.pedidos360.ms_pago.event.PaymentAttemptEvent;
import cl.duoc.pedidos360.ms_pago.service.PaymentAuditService;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consume payment-attempts en el grupo payment-audit-group.
 * Solo traduce el mensaje y delega al service (sin lógica de negocio).
 */
@Component
@RequiredArgsConstructor
public class PaymentAuditListener {

    private final PaymentAuditService paymentAuditService;

    @KafkaListener(topics = KafkaTopics.PAYMENT_ATTEMPTS, groupId = KafkaTopics.PAYMENT_AUDIT_GROUP)
    public void onPaymentAttempt(ConsumerRecord<String, PaymentAttemptEvent> record) {
        paymentAuditService.registrar(record.value(), record.partition(), record.offset());
    }
}
