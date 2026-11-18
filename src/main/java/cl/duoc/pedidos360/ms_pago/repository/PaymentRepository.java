package cl.duoc.pedidos360.ms_pago.repository;

import cl.duoc.pedidos360.ms_pago.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByUserIdOrderByCreatedAtDesc(String userId);
}
