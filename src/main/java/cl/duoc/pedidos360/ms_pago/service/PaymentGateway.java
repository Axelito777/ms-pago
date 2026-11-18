package cl.duoc.pedidos360.ms_pago.service;

import java.math.BigDecimal;

/**
 * Contrato de la pasarela de pago. Hoy se usa SimulatedPaymentGateway;
 * para usar Webpay basta con otra implementación de esta interfaz, sin tocar el resto.
 */
public interface PaymentGateway {

    GatewayResult procesar(String last4, BigDecimal amount, String currency);

    /** Respuesta de la pasarela */
    record GatewayResult(boolean aprobado, String mensaje) {
    }
}
