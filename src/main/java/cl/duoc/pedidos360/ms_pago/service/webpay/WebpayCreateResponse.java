package cl.duoc.pedidos360.ms_pago.service.webpay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Respuesta de crear: el token y la URL del formulario de pago de Webpay */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebpayCreateResponse(
        @JsonProperty("token") String token,
        @JsonProperty("url") String url) {
}
