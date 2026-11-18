package cl.duoc.pedidos360.ms_pago.dto;

import cl.duoc.pedidos360.ms_pago.model.FraudAlert;

import java.time.LocalDateTime;

/** Alerta de fraude para GET /payments/fraud-alerts (solo Admin) */
public record FraudAlertResponse(
        Long id,
        String cardHash,
        String last4,
        String userId,
        int cantidadIntentos,
        LocalDateTime fecha,
        String motivo) {

    public static FraudAlertResponse from(FraudAlert a) {
        return new FraudAlertResponse(a.getId(), a.getCardHash(), a.getLast4(), a.getUserId(),
                a.getCantidadIntentos(), a.getFecha(), a.getMotivo());
    }
}
