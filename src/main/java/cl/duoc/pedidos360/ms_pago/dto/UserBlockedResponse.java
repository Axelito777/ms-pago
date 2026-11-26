package cl.duoc.pedidos360.ms_pago.dto;

import java.time.Instant;

/**
 * Body del 423. Mismo formato en ms-pago, carrito-service y orders-service,
 * para que el frontend lo trate igual venga de donde venga.
 */
public record UserBlockedResponse(String error, Instant until, String message) {

    public static final String CODIGO = "USER_BLOCKED";

    public static UserBlockedResponse hasta(Instant until) {
        return new UserBlockedResponse(CODIGO, until,
                "Tu cuenta está bloqueada temporalmente por actividad sospechosa");
    }
}
