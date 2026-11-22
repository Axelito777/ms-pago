package cl.duoc.pedidos360.ms_pago.service.webpay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/** Respuesta del commit (solo los campos que usamos) */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebpayCommitResponse(
        @JsonProperty("buy_order") String buyOrder,
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("status") String status,
        @JsonProperty("response_code") Integer responseCode,
        @JsonProperty("authorization_code") String authorizationCode,
        @JsonProperty("card_detail") CardDetail cardDetail) {

    /** Éxito según Transbank: response_code 0 y status AUTHORIZED */
    public boolean autorizada() {
        return responseCode != null && responseCode == 0 && "AUTHORIZED".equals(status);
    }

    /** Últimos 4 dígitos de la tarjeta (Transbank ya los manda recortados) */
    public String last4() {
        if (cardDetail == null || cardDetail.cardNumber() == null) {
            return null;
        }
        String numero = cardDetail.cardNumber().replaceAll("\\D", "");
        return numero.length() <= 4 ? numero : numero.substring(numero.length() - 4);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CardDetail(@JsonProperty("card_number") String cardNumber) {
    }
}
