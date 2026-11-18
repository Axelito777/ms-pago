package cl.duoc.pedidos360.ms_pago.service;

import cl.duoc.pedidos360.ms_pago.config.FraudProperties;
import cl.duoc.pedidos360.ms_pago.dto.FraudAlertResponse;
import cl.duoc.pedidos360.ms_pago.dto.PaymentRequest;
import cl.duoc.pedidos360.ms_pago.dto.PaymentResponse;
import cl.duoc.pedidos360.ms_pago.event.PaymentAttemptEvent;
import cl.duoc.pedidos360.ms_pago.event.PaymentEventPublisher;
import cl.duoc.pedidos360.ms_pago.exception.ResourceNotFoundException;
import cl.duoc.pedidos360.ms_pago.model.FraudAlert;
import cl.duoc.pedidos360.ms_pago.model.Payment;
import cl.duoc.pedidos360.ms_pago.model.PaymentStatus;
import cl.duoc.pedidos360.ms_pago.repository.FraudAlertRepository;
import cl.duoc.pedidos360.ms_pago.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Flujo de un intento de pago:
 *  1) tarjeta -> cardHash + last4 (el número y el CVV se descartan aquí)
 *  2) antifraude: si supera el máximo en la ventana -> BLOCKED_FRAUD + FraudAlert (no se llama a la pasarela)
 *  3) si no, la pasarela decide APPROVED o REJECTED
 *  4) se guarda el Payment y se publica el evento en Kafka (si Kafka falla, el pago sigue)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final CardDataService cardDataService;
    private final FraudDetectionService fraudDetectionService;
    private final PaymentGateway paymentGateway;
    private final PaymentRepository paymentRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final PaymentEventPublisher eventPublisher;
    private final FraudProperties fraudProperties;
    private final Clock clock;

    public PaymentResponse procesar(PaymentRequest request, String userId) {
        String cardHash = cardDataService.hash(request.getCardNumber());
        String last4 = cardDataService.last4(request.getCardNumber());

        FraudCheckResult fraude = fraudDetectionService.registrarIntento(cardHash);

        PaymentStatus status;
        String mensaje;
        if (fraude.bloqueado()) {
            status = PaymentStatus.BLOCKED_FRAUD;
            mensaje = "Bloqueado por posible fraude: " + fraude.intentosEnVentana() + " intentos en "
                    + fraudProperties.getWindowSeconds() + " s (máximo " + fraudProperties.getMaxAttempts() + ")";
            registrarAlerta(cardHash, last4, userId, fraude.intentosEnVentana(), mensaje);
        } else {
            PaymentGateway.GatewayResult resultado =
                    paymentGateway.procesar(last4, request.getAmount(), request.getCurrency());
            status = resultado.aprobado() ? PaymentStatus.APPROVED : PaymentStatus.REJECTED;
            mensaje = resultado.mensaje();
        }

        Payment pago = paymentRepository.save(Payment.builder()
                .orderId(request.getOrderId())
                .userId(userId)
                .cardHash(cardHash)
                .last4(last4)
                .cardHolder(request.getCardHolder())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .status(status)
                .fraudSuspected(fraude.bloqueado())
                .message(mensaje)
                .createdAt(LocalDateTime.now(clock))
                .build());

        log.info("Pago {} orden={} usuario={} tarjeta=****{} estado={}",
                pago.getId(), pago.getOrderId(), userId, last4, status);

        eventPublisher.publicar(new PaymentAttemptEvent(
                UUID.randomUUID().toString(),
                pago.getId(),
                pago.getOrderId(),
                pago.getUserId(),
                pago.getCardHash(),
                pago.getLast4(),
                pago.getAmount(),
                pago.getCurrency(),
                pago.getStatus(),
                pago.isFraudSuspected(),
                clock.instant()));

        return PaymentResponse.from(pago);
    }

    @Transactional(readOnly = true)
    public PaymentResponse buscarPorId(Long id) {
        return paymentRepository.findById(id)
                .map(PaymentResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("No existe el pago " + id));
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> listarPorUsuario(String userId) {
        return paymentRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(PaymentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FraudAlertResponse> listarAlertas() {
        return fraudAlertRepository.findAllByOrderByFechaDesc().stream()
                .map(FraudAlertResponse::from)
                .toList();
    }

    private void registrarAlerta(String cardHash, String last4, String userId, int intentos, String motivo) {
        fraudAlertRepository.save(FraudAlert.builder()
                .cardHash(cardHash)
                .last4(last4)
                .userId(userId)
                .cantidadIntentos(intentos)
                .fecha(LocalDateTime.now(clock))
                .motivo(motivo)
                .build());
        // Solo un prefijo del hash en el log: suficiente para correlacionar, sin exponer datos de la tarjeta
        log.warn("POSIBLE FRAUDE: tarjeta ****{} (hash {}...) usuario={} intentos={} en {} s",
                last4, cardHash.substring(0, 12), userId, intentos, fraudProperties.getWindowSeconds());
    }
}
