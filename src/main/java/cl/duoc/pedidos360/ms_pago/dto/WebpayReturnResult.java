package cl.duoc.pedidos360.ms_pago.dto;

/**
 * Resultado del retorno desde Webpay: a dónde redirigir al navegador.
 * paymentId es null y status "UNKNOWN" si no se encontró el pago.
 */
public record WebpayReturnResult(Long paymentId, String status, String redirectUrl) {
}
