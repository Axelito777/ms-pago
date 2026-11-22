package cl.duoc.pedidos360.ms_pago.event;

import cl.duoc.pedidos360.ms_pago.config.KafkaTopics;
import cl.duoc.pedidos360.ms_pago.model.PaymentMethod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publica los intentos de pago en Kafka.
 * Si Kafka falla, solo se loguea: el pago del usuario NO debe fallar por esto.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventPublisher {

    private final KafkaTemplate<String, PaymentAttemptEvent> kafkaTemplate;

    public void publicar(PaymentAttemptEvent evento) {
        try {
            // KEY = cardHash. Kafka elige la partición con hash(key) % particiones, así que
            // todos los intentos de una misma tarjeta caen SIEMPRE en la misma partición.
            // Dentro de una partición Kafka garantiza el orden, por lo que el consumidor ve
            // los intentos de cada tarjeta en el orden en que ocurrieron (clave para auditar fraude).
            // Usamos el hash y no el número: la tarjeta real nunca sale del servicio.
            // En Webpay no conocemos la tarjeta (se escribe en Transbank): la KEY es el userId,
            // así los pagos Webpay de un mismo usuario quedan ordenados en una partición.
            kafkaTemplate.send(KafkaTopics.PAYMENT_ATTEMPTS, key(evento), evento)
                    .whenComplete((resultado, error) -> {
                        // Error asíncrono: el broker no confirmó el mensaje
                        if (error != null) {
                            log.error("No se pudo publicar el intento de pago {} en Kafka: {}",
                                    evento.paymentId(), error.getMessage());
                        } else {
                            log.debug("Intento {} publicado en partición {} offset {}", evento.paymentId(),
                                    resultado.getRecordMetadata().partition(), resultado.getRecordMetadata().offset());
                        }
                    });
        } catch (Exception e) {
            // Error síncrono: por ejemplo, no hay metadata del broker dentro de max.block.ms
            log.error("Kafka no disponible, el intento de pago {} no se publicó: {}",
                    evento.paymentId(), e.getMessage());
        }
    }

    /** cardHash para el pago simulado, userId para Webpay */
    static String key(PaymentAttemptEvent evento) {
        return evento.method() == PaymentMethod.WEBPAY ? evento.userId() : evento.cardHash();
    }
}
