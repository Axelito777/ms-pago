package cl.duoc.pedidos360.ms_pago.service.audit;

import cl.duoc.pedidos360.ms_pago.dto.PaymentAuditResponse;
import cl.duoc.pedidos360.ms_pago.event.PaymentAttemptEvent;

import java.util.List;

/**
 * Dónde se guarda la copia de auditoría de cada intento de pago.
 * Implementaciones: DynamoPaymentAuditStore (real) y NoOpPaymentAuditStore (sin AWS),
 * elegidas con dynamodb.enabled.
 */
public interface PaymentAuditStore {

    void save(PaymentAttemptEvent event);

    /** Intentos del usuario, más recientes primero, como máximo "limit" */
    List<PaymentAuditResponse> findByUser(String userId, int limit);
}
