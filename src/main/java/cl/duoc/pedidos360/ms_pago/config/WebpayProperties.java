package cl.duoc.pedidos360.ms_pago.config;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración de Webpay Plus (prefijo "webpay" en application.properties).
 * Las credenciales llegan SOLO por variables de entorno; no hay valores por defecto en el código.
 */
@Getter
@Setter
@ToString
@ConfigurationProperties(prefix = "webpay")
public class WebpayProperties {

    /** https://webpay3gint.transbank.cl en integración */
    private String baseUrl = "https://webpay3gint.transbank.cl";

    /** Código de comercio (header Tbk-Api-Key-Id) */
    private String commerceCode;

    /** Llave secreta (header Tbk-Api-Key-Secret). Excluida del toString para que nunca termine en un log */
    @ToString.Exclude
    private String apiKey;

    /** URL pública a la que Transbank devuelve al usuario (nuestro /payments/webpay/return vía Gateway) */
    private String returnUrl;

    /** Pantalla de resultado del frontend a la que redirigimos al final */
    private String frontendResultUrl;

    /** Tiempos máximos de espera al hablar con Transbank */
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 30000;

    /** true si están las credenciales y la URL de retorno (lo mínimo para crear una transacción) */
    public boolean puedeIniciar() {
        return tieneValor(commerceCode) && tieneValor(apiKey) && tieneValor(returnUrl);
    }

    public boolean tieneUrlResultado() {
        return tieneValor(frontendResultUrl);
    }

    private static boolean tieneValor(String valor) {
        return valor != null && !valor.isBlank();
    }
}
