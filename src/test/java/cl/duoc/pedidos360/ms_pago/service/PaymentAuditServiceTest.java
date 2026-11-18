package cl.duoc.pedidos360.ms_pago.service;

import cl.duoc.pedidos360.ms_pago.event.PaymentAttemptEvent;
import cl.duoc.pedidos360.ms_pago.model.PaymentAuditLog;
import cl.duoc.pedidos360.ms_pago.model.PaymentStatus;
import cl.duoc.pedidos360.ms_pago.repository.PaymentAuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentAuditServiceTest {

    @Mock
    private PaymentAuditLogRepository repository;

    private final PaymentAttemptEvent evento = new PaymentAttemptEvent("e1", 1L, 10L, "user-1", "hash", "1111",
            new BigDecimal("1000"), "CLP", PaymentStatus.BLOCKED_FRAUD, true, Instant.now());

    @Test
    void guardaAuditoriaConParticionYOffset() {
        PaymentAuditService service = new PaymentAuditService(repository, Clock.systemUTC());
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
        PaymentAuditService service = new PaymentAuditService(repository, Clock.systemUTC());
        when(repository.existsByEventId("e1")).thenReturn(true);

        service.registrar(evento, 0, 1L);

        verify(repository, never()).save(any());
    }
}
