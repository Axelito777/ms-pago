package cl.duoc.pedidos360.ms_pago.event;

import cl.duoc.pedidos360.ms_pago.model.PaymentMethod;
import cl.duoc.pedidos360.ms_pago.model.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Evento publicado en el tópico payment-attempts por CADA intento de pago con resultado
 * (aprobado, rechazado, bloqueado o anulado). No lleva número de tarjeta, CVV ni token de Webpay.
 * En Webpay cardHash viene null (la tarjeta nunca pasa por nosotros).
 * blockedUntil viene SOLO en el intento que bloqueó al usuario (429): ese mensaje es el "evento de bloqueo".
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
        PaymentMethod method,
        Instant blockedUntil) { // hasta cuándo queda bloqueado el usuario; null si este intento no bloqueó

    /** Intento que no bloquea a nadie (blockedUntil = null) */
    public PaymentAttemptEvent(String eventId, Long paymentId, Long orderId, String userId, String cardHash,
                               String last4, BigDecimal amount, String currency, PaymentStatus status,
                               boolean fraudSuspected, Instant timestamp, PaymentMethod method) {
        this(eventId, paymentId, orderId, userId, cardHash, last4, amount, currency, status,
                fraudSuspected, timestamp, method, null);
    }
}
