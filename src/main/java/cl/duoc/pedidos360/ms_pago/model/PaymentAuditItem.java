package cl.duoc.pedidos360.ms_pago.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Ítem de la tabla DynamoDB de auditoría de pagos.
 *  - userId (clave de partición): todos los intentos de un usuario quedan juntos.
 *  - sk (clave de orden): "<fecha ISO UTC>#<paymentId>#<estado>", ordena por fecha.
 * A propósito NO tiene cardHash, número de tarjeta, CVV ni token de Webpay.
 * (No es una entidad JPA: no lleva @Entity, H2 no la ve.)
 */
@Data
@NoArgsConstructor
@DynamoDbBean
public class PaymentAuditItem {

    private String userId;
    private String sk;
    private String eventId;
    private Long paymentId;
    private Long orderId;
    private String last4;
    private BigDecimal amount;
    private String currency;
    private String status;
    private String method;
    private Boolean fraudSuspected;
    private Instant timestamp;

    // Las anotaciones de clave van en el getter (así lo pide el SDK); Lombok no genera estos dos

    @DynamoDbPartitionKey
    public String getUserId() {
        return userId;
    }

    @DynamoDbSortKey
    public String getSk() {
        return sk;
    }
}
