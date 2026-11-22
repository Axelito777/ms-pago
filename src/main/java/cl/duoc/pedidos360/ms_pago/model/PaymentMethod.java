package cl.duoc.pedidos360.ms_pago.model;

/** Medio con que se hizo el pago */
public enum PaymentMethod {
    SIMULATED, // tarjeta escrita en nuestro formulario, pasarela simulada (con antifraude)
    WEBPAY     // Webpay Plus de Transbank: la tarjeta se escribe en el sitio de Transbank
}
