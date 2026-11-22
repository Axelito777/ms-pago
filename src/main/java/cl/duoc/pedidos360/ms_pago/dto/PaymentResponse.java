package cl.duoc.pedidos360.ms_pago.dto;

import cl.duoc.pedidos360.ms_pago.model.Payment;
import cl.duoc.pedidos360.ms_pago.model.PaymentMethod;
import cl.duoc.pedidos360.ms_pago.model.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Respuesta de un pago: solo last4, nunca el número ni el hash completo. Tampoco el token de Webpay */
public record PaymentResponse(
        Long id,
        Long orderId,
        String userId,
        String last4,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        boolean fraudSuspected,
        String message,
        LocalDateTime createdAt,
        PaymentMethod method,
        String buyOrder,
        String authorizationCode) {

    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(p.getId(), p.getOrderId(), p.getUserId(), p.getLast4(), p.getAmount(),
                p.getCurrency(), p.getStatus(), p.isFraudSuspected(), p.getMessage(), p.getCreatedAt(),
                p.getMethod(), p.getBuyOrder(), p.getAuthorizationCode());
    }
}
