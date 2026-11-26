package cl.duoc.pedidos360.ms_pago.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parámetros del antifraude (prefijo "fraud" en application.properties).
 * Con los valores por defecto: hasta 3 intentos por tarjeta o por usuario en 10 s están permitidos;
 * el 4.º responde 429 y deja al usuario bloqueado blockMinutes (mientras tanto, 423).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "fraud")
public class FraudProperties {

    /** Tamaño de la ventana deslizante en segundos */
    private int windowSeconds = 10;

    /** Máximo de intentos permitidos dentro de la ventana */
    private int maxAttempts = 3;

    /** Minutos que queda bloqueado el usuario después de superar el máximo */
    private int blockMinutes = 10;
}
