package cl.duoc.pedidos360.ms_pago.service;

import cl.duoc.pedidos360.ms_pago.event.PaymentAttemptEvent;
import cl.duoc.pedidos360.ms_pago.model.PaymentAuditLog;
import cl.duoc.pedidos360.ms_pago.model.PaymentMethod;
import cl.duoc.pedidos360.ms_pago.model.PaymentStatus;
import cl.duoc.pedidos360.ms_pago.repository.PaymentAuditLogRepository;
import cl.duoc.pedidos360.ms_pago.service.audit.PaymentAuditStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentAuditServiceTest {

    @Mock
    private PaymentAuditLogRepository repository;

    @Mock
    private PaymentAuditStore store;

    private PaymentAuditService service;

    private final PaymentAttemptEvent evento = new PaymentAttemptEvent("e1", 1L, 10L, "user-1", "hash", "1111",
            new BigDecimal("1000"), "CLP", PaymentStatus.BLOCKED_FRAUD, true, Instant.now(), PaymentMethod.SIMULATED);

    @BeforeEach
    void setUp() {
        service = new PaymentAuditService(repository, Clock.systemUTC(), store);
    }

    @Test
    void guardaAuditoriaConParticionYOffset() {
        when(repository.existsByEventId("e1")).thenReturn(false);

        service.registrar(evento, 2, 57L);

        ArgumentCaptor<PaymentAuditLog> log = ArgumentCaptor.forClass(PaymentAuditLog.class);
        verify(repository).save(log.capture());
        assertThat(log.getValue().getKafkaPartition()).isEqualTo(2);
        assertThat(log.getValue().getKafkaOffset()).isEqualTo(57L);
        assertThat(log.getValue().getStatus()).isEqualTo(PaymentStatus.BLOCKED_FRAUD);
        assertThat(log.getValue().isFraudSuspected()).isTrue();
    }

    @Test
    void eventoDuplicado_seIgnora() {
        when(repository.existsByEventId("e1")).thenReturn(true);

        service.registrar(evento, 0, 1L);

        verify(repository, never()).save(any());
        verify(store, never()).save(any());
    }

    // ---------- DynamoDB ----------

    @Test
    void eventoNuevo_tambienSeGuardaEnElStore() {
        when(repository.existsByEventId("e1")).thenReturn(false);

        service.registrar(evento, 0, 1L);

        verify(store).save(evento);
    }

    @Test
    void siDynamoFalla_noLanzaExcepcionYH2QuedaGuardado() {
        when(repository.existsByEventId("e1")).thenReturn(false);
        doThrow(DynamoDbException.builder().message("caído").build()).when(store).save(any());

        // El consumidor de Kafka no debe ver la excepción (si la viera, reintentaría el mensaje)
        assertThatCode(() -> service.registrar(evento, 0, 1L)).doesNotThrowAnyException();
        verify(repository).save(any(PaymentAuditLog.class));
    }

    @Test
    void siElStoreLanzaCualquierRuntimeException_tambienSigue() {
        when(repository.existsByEventId("e1")).thenReturn(false);
        doThrow(new IllegalStateException("x")).when(store).save(any());

        assertThatCode(() -> service.registrar(evento, 0, 1L)).doesNotThrowAnyException();
    }

    @Test
    void eventoSinUserId_noSeGuardaEnDynamo() {
        PaymentAttemptEvent sinUsuario = new PaymentAttemptEvent("e2", 2L, 10L, null, null, "1111",
                new BigDecimal("1000"), "CLP", PaymentStatus.APPROVED, false, Instant.now(), PaymentMethod.WEBPAY);
        when(repository.existsByEventId("e2")).thenReturn(false);

        service.registrar(sinUsuario, 0, 1L);

        verify(repository).save(any(PaymentAuditLog.class));
        verify(store, never()).save(any());
    }

    @Test
    void consulta_ajustaElLimiteEntre1y50() {
        service.consultarPorUsuario("u", 200);
        verify(store).findByUser("u", 50);

        service.consultarPorUsuario("u", 0);
        verify(store).findByUser("u", 1);

        service.consultarPorUsuario("u", 20);
        verify(store).findByUser("u", 20);
    }
}
