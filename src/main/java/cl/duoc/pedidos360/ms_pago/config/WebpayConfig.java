package cl.duoc.pedidos360.ms_pago.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * RestClient para la API REST de Transbank (sin SDK).
 * Con timeouts: si Transbank no responde, init devuelve 502 en vez de dejar colgado al usuario.
 */
@Configuration
public class WebpayConfig {

    @Bean
    public RestClient webpayRestClient(WebpayProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.getReadTimeoutMs()));
        return RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .build();
    }
}
