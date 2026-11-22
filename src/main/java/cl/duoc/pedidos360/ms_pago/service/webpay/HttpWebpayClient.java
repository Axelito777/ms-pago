package cl.duoc.pedidos360.ms_pago.service.webpay;

import cl.duoc.pedidos360.ms_pago.config.WebpayProperties;
import cl.duoc.pedidos360.ms_pago.exception.WebpayGatewayException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.function.Supplier;

/**
 * Cliente HTTP de Webpay Plus con RestClient (sin SDK de Transbank).
 * La llave va solo en el header Tbk-Api-Key-Secret; nunca se loguea, ni tampoco el token.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpWebpayClient implements WebpayClient {

    static final String RUTA_TRANSACCIONES = "/rswebpaytransaction/api/webpay/v1.2/transactions";

    private final RestClient webpayRestClient;
    private final WebpayProperties props;

    @Override
    public WebpayCreateResponse create(String buyOrder, String sessionId, long amount, String returnUrl) {
        WebpayCreateResponse respuesta = llamar("crear", () -> webpayRestClient.post()
                .uri(RUTA_TRANSACCIONES)
                .headers(h -> {
                    h.set("Tbk-Api-Key-Id", props.getCommerceCode());
                    h.set("Tbk-Api-Key-Secret", props.getApiKey());
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(new WebpayCreateRequest(buyOrder, sessionId, amount, returnUrl))
                .retrieve()
                .body(WebpayCreateResponse.class));
        if (respuesta == null || respuesta.token() == null || respuesta.url() == null) {
            throw new WebpayGatewayException("Transbank respondió sin token o sin url");
        }
        return respuesta;
    }

    @Override
    public WebpayCommitResponse commit(String token) {
        // PUT con el token en la URL y sin body
        WebpayCommitResponse respuesta = llamar("confirmar", () -> webpayRestClient.put()
                .uri(RUTA_TRANSACCIONES + "/{token}", token)
                .headers(h -> {
                    h.set("Tbk-Api-Key-Id", props.getCommerceCode());
                    h.set("Tbk-Api-Key-Secret", props.getApiKey());
                })
                .contentType(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(WebpayCommitResponse.class));
        if (respuesta == null) {
            throw new WebpayGatewayException("Transbank respondió vacío al confirmar");
        }
        return respuesta;
    }

    /**
     * Traduce cualquier error HTTP o de red a WebpayGatewayException (502).
     * Solo se loguea el código HTTP o el tipo de error: la URL del commit lleva el token, por eso
     * NO se usa el mensaje de la excepción (podría incluir la URL completa).
     */
    private <T> T llamar(String operacion, Supplier<T> llamada) {
        try {
            return llamada.get();
        } catch (RestClientResponseException e) {
            log.warn("Webpay {}: Transbank respondió HTTP {}", operacion, e.getStatusCode().value());
            throw new WebpayGatewayException("Transbank respondió con error HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            log.warn("Webpay {}: no se pudo comunicar con Transbank ({})", operacion, e.getClass().getSimpleName());
            throw new WebpayGatewayException("No se pudo comunicar con Transbank");
        }
    }
}
