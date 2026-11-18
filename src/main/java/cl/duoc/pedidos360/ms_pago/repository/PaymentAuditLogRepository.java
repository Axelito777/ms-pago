package cl.duoc.pedidos360.ms_pago.repository;

import cl.duoc.pedidos360.ms_pago.model.PaymentAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAuditLogRepository extends JpaRepository<PaymentAuditLog, Long> {

    boolean existsByEventId(String eventId);
}
