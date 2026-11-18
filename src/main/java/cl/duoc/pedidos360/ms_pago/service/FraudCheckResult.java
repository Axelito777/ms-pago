package cl.duoc.pedidos360.ms_pago.service;

/**
 * Resultado del antifraude para un intento.
 * @param bloqueado          true si se superó el máximo de intentos en la ventana
 * @param intentosEnVentana  intentos de la tarjeta dentro de la ventana, contando este
 */
public record FraudCheckResult(boolean bloqueado, int intentosEnVentana) {
}
