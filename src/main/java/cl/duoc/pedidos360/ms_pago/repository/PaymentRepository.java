package cl.duoc.pedidos360.ms_pago.repository;

import cl.duoc.pedidos360.ms_pago.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByUserIdOrderByCreatedAtDesc(String userId);

    /** Webpay: retorno normal (llega token_ws) */
    Optional<Payment> findByWebpayToken(String webpayToken);

    /** Webpay: anulación o tiempo agotado (llega TBK_ORDEN_COMPRA) */
    Optional<Payment> findByBuyOrder(String buyOrder);
}
