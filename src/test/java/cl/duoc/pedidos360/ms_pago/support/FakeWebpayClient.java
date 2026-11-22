package cl.duoc.pedidos360.ms_pago.support;

import cl.duoc.pedidos360.ms_pago.exception.WebpayGatewayException;
import cl.duoc.pedidos360.ms_pago.service.webpay.WebpayClient;
import cl.duoc.pedidos360.ms_pago.service.webpay.WebpayCommitResponse;
import cl.duoc.pedidos360.ms_pago.service.webpay.WebpayCreateResponse;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Webpay falso para los tests (sin red). Hace el papel de Transbank:
 * entrega tokens únicos al crear y responde el commit que el test le configure.
 */
public class FakeWebpayClient implements WebpayClient {

    public static final String URL_FORMULARIO = "https://webpay3gint.transbank.cl/webpayserver/initTransaction";

    /** Último buy_order / session_id / amount recibidos al crear */
    public final List<String> buyOrdersRecibidos = new ArrayList<>();
    public String ultimoSessionId;
    public long ultimoAmount;

    public int commits = 0;
    public boolean fallarCreate = false;
    public boolean fallarCommit = false;

    /** Respuesta del commit; por defecto aprobada por el monto de la última transacción */
    public WebpayCommitResponse respuestaCommit;

    private int contadorTokens = 0;

    @Override
    public WebpayCreateResponse create(String buyOrder, String sessionId, long amount, String returnUrl) {
        if (fallarCreate) {
            throw new WebpayGatewayException("Transbank respondió con error HTTP 500");
        }
        buyOrdersRecibidos.add(buyOrder);
        ultimoSessionId = sessionId;
        ultimoAmount = amount;
        contadorTokens++;
        return new WebpayCreateResponse("01ab" + "f".repeat(56) + contadorTokens, URL_FORMULARIO);
    }

    @Override
    public WebpayCommitResponse commit(String token) {
        commits++;
        if (fallarCommit) {
            throw new WebpayGatewayException("No se pudo comunicar con Transbank");
        }
        return respuestaCommit != null ? respuestaCommit : aprobada(BigDecimal.valueOf(ultimoAmount));
    }

    /** Commit aprobado: response_code 0, AUTHORIZED, tarjeta VISA de pruebas */
    public static WebpayCommitResponse aprobada(BigDecimal monto) {
        return new WebpayCommitResponse("bo", monto, "AUTHORIZED", 0, "1213",
                new WebpayCommitResponse.CardDetail("6623"));
    }

    /** Commit rechazado: response_code -1, FAILED (MASTERCARD de pruebas) */
    public static WebpayCommitResponse rechazada(BigDecimal monto) {
        return new WebpayCommitResponse("bo", monto, "FAILED", -1, "000000",
                new WebpayCommitResponse.CardDetail("0568"));
    }
}
