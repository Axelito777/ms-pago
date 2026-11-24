package cl.duoc.pedidos360.ms_pago.service.audit;

import cl.duoc.pedidos360.ms_pago.dto.PaymentAuditResponse;
import cl.duoc.pedidos360.ms_pago.event.PaymentAttemptEvent;
import cl.duoc.pedidos360.ms_pago.exception.AuditStoreUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Se usa con dynamodb.enabled=false (valor por defecto): no guarda nada y la consulta
 * responde 503. Así los tests y el desarrollo local funcionan sin AWS.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "dynamodb.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpPaymentAuditStore implements PaymentAuditStore {

    @Override
    public void save(PaymentAttemptEvent event) {
        log.debug("DynamoDB desactivado: no se guarda la auditoría del pago {}", event.paymentId());
    }

    @Override
    public List<PaymentAuditResponse> findByUser(String userId, int limit) {
        throw new AuditStoreUnavailableException(
                "La auditoría en DynamoDB está desactivada (DYNAMODB_ENABLED=false)");
    }
}
