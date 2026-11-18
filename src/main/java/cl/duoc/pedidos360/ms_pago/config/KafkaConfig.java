package cl.duoc.pedidos360.ms_pago.config;

import cl.duoc.pedidos360.ms_pago.event.PaymentAttemptEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuración explícita del productor y del consumidor de Kafka.
 */
@Slf4j
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /** Máximo que send() espera la metadata del broker. Corto para que el POST no se cuelgue si Kafka cae. */
    @Value("${kafka.producer.max-block-ms:3000}")
    private int maxBlockMs;

    /** false en el perfil de test: el listener no intenta conectarse a un broker */
    @Value("${spring.kafka.listener.auto-startup:true}")
    private boolean listenerAutoStartup;

    // ------------------------------ PRODUCTOR ------------------------------

    @Bean
    public ProducerFactory<String, PaymentAttemptEvent> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
        // JSON limpio, sin headers con el nombre de la clase Java (otros servicios no dependen de nuestras clases)
        props.put(JacksonJsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        // acks=all: el broker confirma solo cuando todas las réplicas en sync (mín. 2) tienen el mensaje
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        // Idempotente: si el productor reintenta, Kafka no guarda el mensaje duplicado
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlockMs);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, PaymentAttemptEvent> kafkaTemplate(ProducerFactory<String, PaymentAttemptEvent> pf) {
        return new KafkaTemplate<>(pf);
    }

    // ------------------------------ CONSUMIDOR ------------------------------

    @Bean
    public ConsumerFactory<String, PaymentAttemptEvent> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, KafkaTopics.PAYMENT_AUDIT_GROUP);
        // Si el grupo es nuevo, lee desde el principio del tópico
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // ErrorHandlingDeserializer envuelve al real: un mensaje corrupto no deja al consumidor en un loop infinito
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JacksonJsonDeserializer.class);
        // Como el productor no manda headers de tipo, indicamos a qué clase convertir el JSON
        props.put(JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, PaymentAttemptEvent.class.getName());
        props.put(JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        props.put(JacksonJsonDeserializer.TRUSTED_PACKAGES, "cl.duoc.pedidos360.ms_pago.event");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PaymentAttemptEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, PaymentAttemptEvent> cf, DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, PaymentAttemptEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cf);
        // RECORD: confirma el offset después de procesar cada mensaje
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(errorHandler);
        factory.setAutoStartup(listenerAutoStartup);
        return factory;
    }

    /**
     * Si el listener falla: reintenta 2 veces con 1 s de espera. Si sigue fallando,
     * solo loguea y pasa al siguiente mensaje (sin DLT, por decisión del proyecto).
     */
    @Bean
    public DefaultErrorHandler errorHandler() {
        return new DefaultErrorHandler(
                (record, ex) -> log.error("Mensaje descartado tras reintentos. tópico={} partición={} offset={} error={}",
                        record.topic(), record.partition(), record.offset(), ex.getMessage()),
                new FixedBackOff(1000L, 2L));
    }
}
