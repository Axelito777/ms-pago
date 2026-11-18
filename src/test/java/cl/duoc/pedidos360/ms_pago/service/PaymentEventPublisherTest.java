package cl.duoc.pedidos360.ms_pago.service;

import cl.duoc.pedidos360.ms_pago.config.KafkaTopics;
import cl.duoc.pedidos360.ms_pago.event.PaymentAttemptEvent;
import cl.duoc.pedidos360.ms_pago.event.PaymentEventPublisher;
import cl.duoc.pedidos360.ms_pago.model.PaymentStatus;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Si Kafka falla, publicar() nunca propaga la excepción (el POST /payments no debe fallar) */
@ExtendWith(MockitoExtension.class)
class PaymentEventPublisherTest {

    @Mock
    private KafkaTemplate<String, PaymentAttemptEvent> kafkaTemplate;

    @InjectMocks
    private PaymentEventPublisher publisher;

    private final PaymentAttemptEvent evento = new PaymentAttemptEvent("e1", 1L, 10L, "user-1", "hash-abc", "1111",
            new BigDecimal("1000"), "CLP", PaymentStatus.APPROVED, false, Instant.now());

    @Test
    void usaCardHashComoKey() {
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(new CompletableFuture<>());

        publisher.publicar(evento);

        verify(kafkaTemplate).send(eq(KafkaTopics.PAYMENT_ATTEMPTS), eq("hash-abc"), eq(evento));
    }

    @Test
    void errorSincrono_noPropaga() {
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenThrow(new KafkaException("broker caído"));

        assertThatCode(() -> publisher.publicar(evento)).doesNotThrowAnyException();
    }

    @Test
    void errorAsincrono_noPropaga() {
        CompletableFuture<SendResult<String, PaymentAttemptEvent>> fallido =
                CompletableFuture.failedFuture(new KafkaException("timeout"));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(fallido);

        assertThatCode(() -> publisher.publicar(evento)).doesNotThrowAnyException();
    }
}
