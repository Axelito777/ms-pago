package cl.duoc.pedidos360.ms_pago.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Un intento de pago tal como se devuelve en GET /payments/audit (sin datos sensibles de tarjeta) */
public record PaymentAuditResponse(
        String eventId,
        Long paymentId,
        Long orderId,
        String last4,
        BigDecimal amount,
        String currency,
        String status,
        String method,
        Boolean fraudSuspected,
        Instant timestamp) {
}
