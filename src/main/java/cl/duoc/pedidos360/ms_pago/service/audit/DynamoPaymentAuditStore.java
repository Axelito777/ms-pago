package cl.duoc.pedidos360.ms_pago.service.audit;

import cl.duoc.pedidos360.ms_pago.dto.PaymentAuditResponse;
import cl.duoc.pedidos360.ms_pago.event.PaymentAttemptEvent;
import cl.duoc.pedidos360.ms_pago.exception.AuditStoreUnavailableException;
import cl.duoc.pedidos360.ms_pago.model.PaymentAuditItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Guarda y consulta la auditoría en DynamoDB (activo con dynamodb.enabled=true).
 * La tabla la crea el alumno con la CLI; aquí solo se usa (putItem y query).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "dynamodb.enabled", havingValue = "true")
public class DynamoPaymentAuditStore implements PaymentAuditStore {

    /**
     * Fecha de sk SIEMPRE en UTC y con milisegundos (largo fijo). Instant.toString() omite los
     * decimales cuando son cero y entonces el orden alfabético de sk dejaría de ser cronológico.
     */
    static final DateTimeFormatter FECHA_SK =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final DynamoDbTable<PaymentAuditItem> tabla;

    public DynamoPaymentAuditStore(DynamoDbEnhancedClient enhancedClient,
                                   @Value("${dynamodb.table}") String nombreTabla) {
        this.tabla = enhancedClient.table(nombreTabla, TableSchema.fromBean(PaymentAuditItem.class));
    }

    /** putItem: si llega dos veces el mismo intento, la clave es igual y el ítem se reemplaza (no se duplica) */
    @Override
    public void save(PaymentAttemptEvent event) {
        tabla.putItem(aItem(event));
    }

    @Override
    public List<PaymentAuditResponse> findByUser(String userId, int limit) {
        QueryEnhancedRequest consulta = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(Key.builder().partitionValue(userId).build()))
                .scanIndexForward(false) // sk descendente = más recientes primero
                .limit(limit)
                .build();
        try {
            return tabla.query(consulta).items().stream()
                    .limit(limit) // corta aquí para no pedir más páginas de las necesarias
                    .map(DynamoPaymentAuditStore::aRespuesta)
                    .toList();
        } catch (SdkException e) {
            log.warn("No se pudo consultar la auditoría en DynamoDB: {}", e.getClass().getSimpleName());
            throw new AuditStoreUnavailableException("No se pudo consultar la auditoría en DynamoDB, intenta más tarde");
        }
    }

    /** "<fecha ISO UTC>#<paymentId>#<estado>", ej. 2026-11-23T14:05:09.000Z#7#APPROVED */
    static String construirSk(Instant fecha, Long paymentId, String estado) {
        return FECHA_SK.format(fecha) + "#" + paymentId + "#" + estado;
    }

    /** Copia solo los campos permitidos del evento (cardHash queda fuera a propósito) */
    static PaymentAuditItem aItem(PaymentAttemptEvent e) {
        if (e.userId() == null || e.userId().isBlank() || e.timestamp() == null || e.status() == null) {
            throw new IllegalArgumentException("evento sin userId, timestamp o status");
        }
        PaymentAuditItem item = new PaymentAuditItem();
        item.setUserId(e.userId());
        item.setSk(construirSk(e.timestamp(), e.paymentId(), e.status().name()));
        item.setEventId(e.eventId());
        item.setPaymentId(e.paymentId());
        item.setOrderId(e.orderId());
        item.setLast4(e.last4());
        item.setAmount(e.amount());
        item.setCurrency(e.currency());
        item.setStatus(e.status().name());
        item.setMethod(e.method() == null ? null : e.method().name());
        item.setFraudSuspected(e.fraudSuspected());
        item.setTimestamp(e.timestamp());
        return item;
    }

    private static PaymentAuditResponse aRespuesta(PaymentAuditItem i) {
        return new PaymentAuditResponse(i.getEventId(), i.getPaymentId(), i.getOrderId(), i.getLast4(),
                i.getAmount(), i.getCurrency(), i.getStatus(), i.getMethod(), i.getFraudSuspected(), i.getTimestamp());
    }
}
