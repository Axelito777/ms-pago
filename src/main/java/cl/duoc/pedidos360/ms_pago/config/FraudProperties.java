package cl.duoc.pedidos360.ms_pago.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parámetros del antifraude (prefijo "fraud" en application.properties).
 * Con los valores por defecto: hasta 3 intentos por tarjeta en 10 s están permitidos; el 4.º se bloquea.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "fraud")
public class FraudProperties {

    /** Tamaño de la ventana deslizante en segundos */
    private int windowSeconds = 10;

    /** Máximo de intentos permitidos dentro de la ventana */
    private int maxAttempts = 3;
}
