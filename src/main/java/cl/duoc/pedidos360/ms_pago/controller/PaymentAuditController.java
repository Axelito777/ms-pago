package cl.duoc.pedidos360.ms_pago.controller;

import cl.duoc.pedidos360.ms_pago.dto.PaymentAuditResponse;
import cl.duoc.pedidos360.ms_pago.exception.UnauthorizedException;
import cl.duoc.pedidos360.ms_pago.security.JwtClaims;
import cl.duoc.pedidos360.ms_pago.service.PaymentAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Auditoría de intentos de pago guardada en DynamoDB.
 * El usuario sale SIEMPRE del claim oid del token (ya validado por SecurityConfig):
 * no hay parámetro userId, así que cada uno solo ve sus propios intentos.
 */
@RestController
@RequestMapping("/payments/audit")
@RequiredArgsConstructor
public class PaymentAuditController {

    private final PaymentAuditService paymentAuditService;
    private final JwtClaims jwtClaims;

    @GetMapping
    public List<PaymentAuditResponse> misIntentos(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(defaultValue = "20") int limit) {
        String userId = jwtClaims.extraerClaim(authHeader, "oid");
        if (userId == null) {
            throw new UnauthorizedException("El token no contiene el claim 'oid'");
        }
        return paymentAuditService.consultarPorUsuario(userId, limit);
    }
}
