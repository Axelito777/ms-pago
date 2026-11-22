package cl.duoc.pedidos360.ms_pago.service.webpay;

/**
 * Contrato con la API de Webpay Plus. La implementación real es HttpWebpayClient;
 * los tests usan un cliente falso y así nunca tocan la red.
 */
public interface WebpayClient {

    /**
     * Crea la transacción en Transbank.
     * @throws cl.duoc.pedidos360.ms_pago.exception.WebpayGatewayException si Transbank falla o no responde
     */
    WebpayCreateResponse create(String buyOrder, String sessionId, long amount, String returnUrl);

    /**
     * Confirma (commit) la transacción. Transbank solo acepta un commit por token.
     * @throws cl.duoc.pedidos360.ms_pago.exception.WebpayGatewayException si Transbank falla o no responde
     */
    WebpayCommitResponse commit(String token);
}
