package cl.duoc.pedidos360.ms_pago.model;

/** Resultado de un intento de pago */
public enum PaymentStatus {
    APPROVED,      // la pasarela aprobó        -> HTTP 201
    REJECTED,      // la pasarela rechazó        -> HTTP 402
    BLOCKED_FRAUD  // bloqueado por antifraude   -> HTTP 429 (no llega a la pasarela)
}
