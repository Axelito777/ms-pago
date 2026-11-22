package cl.duoc.pedidos360.ms_pago.model;

/** Estado de un pago */
public enum PaymentStatus {
    APPROVED,      // la pasarela aprobó        -> HTTP 201
    REJECTED,      // la pasarela rechazó        -> HTTP 402
    BLOCKED_FRAUD, // bloqueado por antifraude   -> HTTP 429 (no llega a la pasarela)
    PENDING,       // Webpay: el usuario está pagando en el sitio de Transbank
    CANCELLED      // Webpay: el usuario anuló o se agotó el tiempo en Transbank
}
