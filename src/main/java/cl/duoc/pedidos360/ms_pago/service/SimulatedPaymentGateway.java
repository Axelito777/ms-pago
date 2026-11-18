package cl.duoc.pedidos360.ms_pago.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Pasarela simulada: aprueba todo, salvo tarjetas que terminan en "0000" (para probar rechazos).
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    static final String SUFIJO_RECHAZO = "0000";

    @Override
    public GatewayResult procesar(String last4, BigDecimal amount, String currency) {
        if (SUFIJO_RECHAZO.equals(last4)) {
            return new GatewayResult(false, "Pago rechazado por el emisor (simulado)");
        }
        return new GatewayResult(true, "Pago aprobado (simulado)");
    }
}
