package cl.duoc.pedidos360.ms_pago.dto;

/**
 * Respuesta 201 de init. El frontend envía al usuario a url con un formulario POST que lleva token_ws = token.
 */
public record WebpayInitResponse(Long paymentId, String token, String url, String buyOrder) {
}
