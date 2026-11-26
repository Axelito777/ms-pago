package cl.duoc.pedidos360.ms_pago.controller;

import cl.duoc.pedidos360.ms_pago.dto.FraudAlertResponse;
import cl.duoc.pedidos360.ms_pago.dto.FraudStatusResponse;
import cl.duoc.pedidos360.ms_pago.dto.PaymentRequest;
import cl.duoc.pedidos360.ms_pago.dto.PaymentResponse;
import cl.duoc.pedidos360.ms_pago.model.PaymentStatus;
import cl.duoc.pedidos360.ms_pago.security.JwtClaims;
import cl.duoc.pedidos360.ms_pago.service.PaymentService;
import cl.duoc.pedidos360.ms_pago.service.UserBlockService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoints de pagos. El body se valida (@Valid) ANTES de llegar al service,
 * por eso un 400 nunca cuenta como intento en la ventana antifraude.
 */
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final UserBlockService userBlockService;
    private final JwtClaims jwtClaims;

    @PostMapping
    public ResponseEntity<PaymentResponse> pagar(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody PaymentRequest request) {
        String userId = jwtClaims.resolverUserId(authHeader, request.getUserId());
        PaymentResponse respuesta = paymentService.procesar(request, userId);
        return ResponseEntity.status(codigoHttp(respuesta.status())).body(respuesta);
    }

    @GetMapping("/{id}")
    public PaymentResponse buscar(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                  @PathVariable Long id) {
        PaymentResponse pago = paymentService.buscarPorId(id);
        jwtClaims.verificarAcceso(authHeader, pago.userId());
        return pago;
    }

    @GetMapping
    public List<PaymentResponse> listarPorUsuario(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(required = false) String userId) {
        return paymentService.listarPorUsuario(jwtClaims.resolverUserId(authHeader, userId));
    }

    @GetMapping("/fraud-alerts")
    public List<FraudAlertResponse> alertas(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        jwtClaims.exigirAdmin(authHeader);
        return paymentService.listarAlertas();
    }

    /**
     * Estado de bloqueo del usuario. Lo usa el frontend al recargar y lo consultan carrito-service
     * y orders-service reenviando el JWT del usuario. Solo el dueño o un Admin (si no, 403).
     */
    @GetMapping("/fraud-status/{userId}")
    public FraudStatusResponse estadoFraude(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String userId) {
        jwtClaims.verificarAcceso(authHeader, userId);
        return userBlockService.bloqueadoHasta(userId)
                .map(hasta -> new FraudStatusResponse(true, hasta))
                .orElse(new FraudStatusResponse(false, null));
    }

    /**
     * Desbloqueo manual para la demo. Exige el rol "Admin" en el claim "roles",
     * igual que kafka-admin-service, así un mismo token sirve en ambos. 204 siempre (idempotente).
     */
    @DeleteMapping("/fraud-blocks/{userId}")
    public ResponseEntity<Void> desbloquear(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String userId) {
        jwtClaims.exigirAdmin(authHeader);
        userBlockService.desbloquear(userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * APPROVED -> 201, REJECTED -> 402, BLOCKED_FRAUD -> 429.
     * PENDING y CANCELLED son solo de Webpay; el pago simulado nunca los produce.
     */
    private HttpStatus codigoHttp(PaymentStatus status) {
        return switch (status) {
            case APPROVED -> HttpStatus.CREATED;
            case REJECTED -> HttpStatus.PAYMENT_REQUIRED;
            case BLOCKED_FRAUD -> HttpStatus.TOO_MANY_REQUESTS;
            case PENDING, CANCELLED -> HttpStatus.OK;
        };
    }
}
