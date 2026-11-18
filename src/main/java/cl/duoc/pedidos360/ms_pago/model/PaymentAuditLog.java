package cl.duoc.pedidos360.ms_pago.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Registro de auditoría guardado por el consumidor de Kafka (payment-audit-group).
 * eventId es único: si Kafka entrega dos veces el mismo evento, no se duplica.
 * Se guardan partición y offset para mostrar que una misma tarjeta siempre cae en la misma partición.
 */
@Entity
@Table(name = "payment_audit_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 36)
    private String eventId;

    private Long paymentId;
    private Long orderId;
    private String userId;

    @Column(length = 64)
    private String cardHash;

    @Column(length = 4)
    private String last4;

    @Column(precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    private boolean fraudSuspected;

    /** Momento del intento (viene en el evento) */
    private Instant eventTimestamp;

    /** Partición y offset de Kafka donde llegó el evento */
    private int kafkaPartition;
    private long kafkaOffset;

    /** Momento en que el consumidor lo guardó */
    private LocalDateTime receivedAt;
}
