package cl.duoc.pedidos360.ms_pago.exception;

/** Faltan variables de entorno de Webpay (credenciales o URLs) -> 503 */
public class WebpayNotConfiguredException extends RuntimeException {
    public WebpayNotConfiguredException(String message) {
        super(message);
    }
}
