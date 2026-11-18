package cl.duoc.pedidos360.ms_pago.repository;

import cl.duoc.pedidos360.ms_pago.model.FraudAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FraudAlertRepository extends JpaRepository<FraudAlert, Long> {

    List<FraudAlert> findAllByOrderByFechaDesc();
}
