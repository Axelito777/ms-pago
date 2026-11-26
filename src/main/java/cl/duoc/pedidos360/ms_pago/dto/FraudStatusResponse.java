package cl.duoc.pedidos360.ms_pago.dto;

import java.time.Instant;

/** GET /payments/fraud-status/{userId}: until es null si no está bloqueado */
public record FraudStatusResponse(boolean blocked, Instant until) {
}
