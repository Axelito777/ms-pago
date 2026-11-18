package cl.duoc.pedidos360.ms_pago.service;

import cl.duoc.pedidos360.ms_pago.event.PaymentAttemptEvent;
import cl.duoc.pedidos360.ms_pago.model.PaymentAuditLog;
import cl.duoc.pedidos360.ms_pago.repository.PaymentAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Guarda la auditoría de cada intento de pago recibido desde Kafka.
 * Es idempotente: Kafka garantiza entrega "al menos una vez", así que un mismo
 * evento puede llegar dos veces; si el eventId ya existe, se ignora.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentAuditService {

    private final PaymentAuditLogRepository auditRepository;
    private final Clock clock;

    public void registrar(PaymentAttemptEvent evento, int particion, long offset) {
        if (auditRepository.existsByEventId(evento.eventId())) {
            log.info("Evento {} ya auditado, se ignora (entrega duplicada)", evento.eventId());
            return;
        }
        try {
            auditRepository.save(PaymentAuditLog.builder()
                    .eventId(evento.eventId())
                    .paymentId(evento.paymentId())
                    .orderId(evento.orderId())
                    .userId(evento.userId())
                    .cardHash(evento.cardHash())
                    .last4(evento.last4())
                    .amount(evento.amount())
                    .currency(evento.currency())
                    .status(evento.status())
                    .fraudSuspected(evento.fraudSuspected())
                    .eventTimestamp(evento.timestamp())
                    .kafkaPartition(particion)
                    .kafkaOffset(offset)
                    .receivedAt(LocalDateTime.now(clock))
                    .build());
            log.info("Auditoría: pago {} estado={} partición={} offset={}",
                    evento.paymentId(), evento.status(), particion, offset);
        } catch (DataIntegrityViolationException e) {
            // Dos entregas simultáneas del mismo evento: la restricción unique de eventId evita el duplicado
            log.info("Evento {} ya auditado (carrera), se ignora", evento.eventId());
        }
    }
}
