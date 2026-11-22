package cl.duoc.pedidos360.ms_pago.service.webpay;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Body de POST .../transactions (nombres en snake_case, como los pide Transbank) */
public record WebpayCreateRequest(
        @JsonProperty("buy_order") String buyOrder,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("amount") long amount,
        @JsonProperty("return_url") String returnUrl) {
}
