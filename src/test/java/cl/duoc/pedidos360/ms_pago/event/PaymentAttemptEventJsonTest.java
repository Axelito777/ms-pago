package cl.duoc.pedidos360.ms_pago.event;

import cl.duoc.pedidos360.ms_pago.config.KafkaTopics;
import cl.duoc.pedidos360.ms_pago.model.PaymentMethod;
import cl.duoc.pedidos360.ms_pago.model.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** El evento de bloqueo viaja por payment-attempts con blockedUntil, y los mensajes antiguos siguen leyéndose */
class PaymentAttemptEventJsonTest {

    private static final Instant HASTA = Instant.parse("2026-11-26T16:40:00Z");

    private static JacksonJsonDeserializer<PaymentAttemptEvent> deserializador() {
        JacksonJsonDeserializer<PaymentAttemptEvent> d = new JacksonJsonDeserializer<>();
        d.configure(Map.of(
                JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, PaymentAttemptEvent.class.getName(),
                JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS, false,
                JacksonJsonDeserializer.TRUSTED_PACKAGES, "cl.duoc.pedidos360.ms_pago.event"), false);
        return d;
    }

    @Test
    void eventoDeBloqueo_llevaBlockedUntilIso_yVuelveIgual() {
        PaymentAttemptEvent evento = new PaymentAttemptEvent("e1", 1L, 10L, "oid-1", "hash", "1111",
                new BigDecimal("25990"), "CLP", PaymentStatus.BLOCKED_FRAUD, true,
                Instant.parse("2026-11-26T16:30:00Z"), PaymentMethod.SIMULATED, HASTA);

        try (JacksonJsonSerializer<PaymentAttemptEvent> s = new JacksonJsonSerializer<>();
             JacksonJsonDeserializer<PaymentAttemptEvent> d = deserializador()) {
            byte[] bytes = s.serialize(KafkaTopics.PAYMENT_ATTEMPTS, evento);
            assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("\"blockedUntil\":\"2026-11-26T16:40:00Z\"");
            assertThat(d.deserialize(KafkaTopics.PAYMENT_ATTEMPTS, bytes).blockedUntil()).isEqualTo(HASTA);
        }
    }

    @Test
    void mensajeAntiguoSinBlockedUntil_seLeeConNull() {
        String json = """
                {"eventId":"e0","paymentId":1,"orderId":10,"userId":"oid-1","cardHash":"h","last4":"1111",
                 "amount":25990,"currency":"CLP","status":"APPROVED","fraudSuspected":false,
                 "timestamp":"2026-11-20T10:00:00Z","method":"SIMULATED"}
                """;
        try (JacksonJsonDeserializer<PaymentAttemptEvent> d = deserializador()) {
            PaymentAttemptEvent e = d.deserialize(KafkaTopics.PAYMENT_ATTEMPTS, json.getBytes(StandardCharsets.UTF_8));
            assertThat(e.blockedUntil()).isNull();
            assertThat(e.status()).isEqualTo(PaymentStatus.APPROVED);
        }
    }
}
