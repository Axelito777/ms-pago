package cl.duoc.pedidos360.ms_pago.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.time.Duration;

/**
 * Clientes de DynamoDB. Solo se crean con dynamodb.enabled=true (así los tests y el desarrollo
 * local no necesitan AWS). No se configuran credenciales: el SDK usa su cadena por defecto
 * (variables de entorno, ~/.aws o, en la EC2, el perfil de instancia / rol IAM).
 */
@Configuration
@ConditionalOnProperty(name = "dynamodb.enabled", havingValue = "true")
public class DynamoDbConfig {

    /** Tiempo máximo total por llamada (con reintentos): un DynamoDB lento no debe frenar al consumidor de Kafka */
    private static final Duration TIMEOUT_LLAMADA = Duration.ofSeconds(3);
    /** Tiempo máximo de cada intento individual */
    private static final Duration TIMEOUT_INTENTO = Duration.ofSeconds(1);

    @Bean
    public DynamoDbClient dynamoDbClient(@Value("${aws.region}") String region) {
        return DynamoDbClient.builder()
                .region(Region.of(region))
                .overrideConfiguration(c -> c
                        .apiCallTimeout(TIMEOUT_LLAMADA)
                        .apiCallAttemptTimeout(TIMEOUT_INTENTO))
                .build();
    }

    /** Cliente "enhanced": traduce PaymentAuditItem <-> ítems de DynamoDB */
    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
        return DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
    }
}
