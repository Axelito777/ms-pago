package cl.duoc.pedidos360.ms_pago.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Reloj del sistema como bean. En los tests se reemplaza por un reloj controlable
 * para simular el paso del tiempo sin usar Thread.sleep.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
