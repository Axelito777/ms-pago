package cl.duoc.pedidos360.ms_pago.event;

import cl.duoc.pedidos360.ms_pago.model.PaymentMethod;
import cl.duoc.pedidos360.ms_pago.model.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Evento publicado en el tópico payment-attempts por CADA intento de pago con resultado
 * (aprobado, rechazado, bloqueado o anulado). No lleva número de tarjeta, CVV ni token de Webpay.
 * En Webpay cardHash viene null (la tarjeta nunca pasa por nosotros).
 */
public record PaymentAttemptEvent(
        String eventId,       // UUID único del evento (sirve para no duplicar en el consumidor)
        Long paymentId,
        Long orderId,
        String userId,
        String cardHash,
        String last4,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        boolean fraudSuspected,
        Instant timestamp,
        PaymentMethod method) {
}
