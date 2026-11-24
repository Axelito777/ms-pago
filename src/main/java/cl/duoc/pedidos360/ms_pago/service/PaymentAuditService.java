package cl.duoc.pedidos360.ms_pago.service;

import cl.duoc.pedidos360.ms_pago.dto.PaymentAuditResponse;
import cl.duoc.pedidos360.ms_pago.event.PaymentAttemptEvent;
import cl.duoc.pedidos360.ms_pago.model.PaymentAuditLog;
import cl.duoc.pedidos360.ms_pago.repository.PaymentAuditLogRepository;
import cl.duoc.pedidos360.ms_pago.service.audit.PaymentAuditStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Guarda la auditoría de cada intento de pago recibido desde Kafka.
 * Es idempotente: Kafka garantiza entrega "al menos una vez", así que un mismo
 * evento puede llegar dos veces; si el eventId ya existe, se ignora.
 * Además guarda una copia en DynamoDB (PaymentAuditStore). Si DynamoDB falla, solo se loguea:
 * nunca bloquea al consumidor de Kafka.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentAuditService {

    private final PaymentAuditLogRepository auditRepository;
    private final Clock clock;
    private final PaymentAuditStore auditStore;

    /** Límite máximo de intentos que devuelve GET /payments/audit */
    static final int LIMITE_MAXIMO = 50;

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
            guardarEnDynamo(evento);
        } catch (DataIntegrityViolationException e) {
            // Dos entregas simultáneas del mismo evento: la restricción unique de eventId evita el duplicado
            log.info("Evento {} ya auditado (carrera), se ignora", evento.eventId());
        }
    }

    /** Intentos del usuario desde DynamoDB; limit fuera de rango se ajusta a 1..50 */
    public List<PaymentAuditResponse> consultarPorUsuario(String userId, int limit) {
        int limiteAjustado = Math.max(1, Math.min(limit, LIMITE_MAXIMO));
        return auditStore.findByUser(userId, limiteAjustado);
    }

    /**
     * Copia en DynamoDB. Cualquier error se captura y se loguea SIN datos sensibles
     * (solo paymentId, estado y tipo de error): Kafka no reintenta y el consumidor sigue.
     */
    private void guardarEnDynamo(PaymentAttemptEvent evento) {
        if (evento.userId() == null || evento.userId().isBlank()) {
            log.warn("Pago {} sin userId: no se guarda en DynamoDB (la clave de partición es obligatoria)",
                    evento.paymentId());
            return;
        }
        try {
            auditStore.save(evento);
        } catch (Exception e) {
            log.warn("No se pudo guardar en DynamoDB la auditoría del pago {} (estado={}): {}",
                    evento.paymentId(), evento.status(), e.getClass().getSimpleName());
        }
    }
}
