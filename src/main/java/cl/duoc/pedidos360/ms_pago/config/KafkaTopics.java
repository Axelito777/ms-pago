package cl.duoc.pedidos360.ms_pago.config;

/**
 * Nombres de tópicos y grupos de Kafka (nada de strings sueltos en el código).
 * El tópico lo crea kafka-infra/create-topics.sh; este servicio NO crea tópicos.
 */
public final class KafkaTopics {

    public static final String PAYMENT_ATTEMPTS = "payment-attempts";

    /** Grupo del consumidor que guarda la auditoría de intentos de pago */
    public static final String PAYMENT_AUDIT_GROUP = "payment-audit-group";

    private KafkaTopics() {
    }
}
