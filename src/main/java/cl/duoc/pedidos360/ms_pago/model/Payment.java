package cl.duoc.pedidos360.ms_pago.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Intento de pago. NUNCA guarda el número de tarjeta ni el CVV:
 * solo cardHash (SHA-256 del número) y last4.
 */
@Entity
@Table(name = "payments", indexes = @Index(name = "idx_payments_user", columnList = "userId"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false, length = 64)
    private String cardHash;

    @Column(nullable = false, length = 4)
    private String last4;

    private String cardHolder;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    private boolean fraudSuspected;

    /** Mensaje de la pasarela o motivo del bloqueo */
    private String message;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
