package cl.duoc.pedidos360.ms_pago.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Alerta creada por cada intento bloqueado por posible fraude */
@Entity
@Table(name = "fraud_alerts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FraudAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String cardHash;

    @Column(nullable = false, length = 4)
    private String last4;

    private String userId;

    /** Intentos de la tarjeta dentro de la ventana, contando este */
    private int cantidadIntentos;

    @Column(nullable = false)
    private LocalDateTime fecha;

    @Column(nullable = false)
    private String motivo;
}
