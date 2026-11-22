package cl.duoc.pedidos360.ms_pago.exception;

/** Transbank falló, respondió con error o no respondió -> 502. El mensaje nunca incluye token ni llave */
public class WebpayGatewayException extends RuntimeException {
    public WebpayGatewayException(String message) {
        super(message);
    }
}
