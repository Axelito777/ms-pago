package cl.duoc.pedidos360.ms_pago.controller;

import cl.duoc.pedidos360.ms_pago.config.SecurityConfig;
import cl.duoc.pedidos360.ms_pago.dto.PaymentAuditResponse;
import cl.duoc.pedidos360.ms_pago.exception.AuditStoreUnavailableException;
import cl.duoc.pedidos360.ms_pago.security.JwtClaims;
import cl.duoc.pedidos360.ms_pago.service.PaymentAuditService;
import cl.duoc.pedidos360.ms_pago.support.TokenDePrueba;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /payments/audit: seguridad real (tokens firmados con la llave de prueba) y service simulado */
@WebMvcTest(PaymentAuditController.class)
@Import({JwtClaims.class, SecurityConfig.class})
class PaymentAuditControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentAuditService paymentAuditService;

    @TestBean
    private JwtDecoder jwtDecoder;

    static JwtDecoder jwtDecoder() {
        return TokenDePrueba.decoder();
    }

    private static final PaymentAuditResponse INTENTO = new PaymentAuditResponse("ev-1", 7L, 55L, "6623",
            new BigDecimal("25990"), "CLP", "APPROVED", "WEBPAY", false, Instant.parse("2026-11-23T14:05:09Z"));

    @Test
    void sinToken_401() throws Exception {
        mockMvc.perform(get("/payments/audit"))
                .andExpect(status().isUnauthorized());
        verify(paymentAuditService, never()).consultarPorUsuario(anyString(), anyInt());
    }

    @Test
    void sinScopeRead_403() throws Exception {
        mockMvc.perform(get("/payments/audit")
                        .header("Authorization", TokenDePrueba.bearer(Map.of("oid", "oid-user", "scp", "payments.write"))))
                .andExpect(status().isForbidden());
        verify(paymentAuditService, never()).consultarPorUsuario(anyString(), anyInt());
    }

    @Test
    void conScopeRead_200_conLosDatosDelUsuarioDelToken() throws Exception {
        when(paymentAuditService.consultarPorUsuario("oid-user", 20)).thenReturn(List.of(INTENTO));

        mockMvc.perform(get("/payments/audit")
                        .header("Authorization", TokenDePrueba.bearer(Map.of("oid", "oid-user", "scp", "payments.read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].paymentId").value(7))
                .andExpect(jsonPath("$[0].last4").value("6623"))
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andExpect(jsonPath("$[0].cardHash").doesNotExist());
    }

    @Test
    void unUserIdEnLaQueryNoPermiteVerLosDeOtro() throws Exception {
        mockMvc.perform(get("/payments/audit?userId=otro-usuario&limit=5")
                        .header("Authorization", TokenDePrueba.bearer(Map.of("oid", "oid-user", "scp", "payments.read"))))
                .andExpect(status().isOk());
        // Siempre el oid del token, nunca el parámetro
        verify(paymentAuditService).consultarPorUsuario("oid-user", 5);
    }

    @Test
    void dynamoDesactivado_503() throws Exception {
        when(paymentAuditService.consultarPorUsuario("oid-user", 20))
                .thenThrow(new AuditStoreUnavailableException("La auditoría en DynamoDB está desactivada (DYNAMODB_ENABLED=false)"));

        mockMvc.perform(get("/payments/audit")
                        .header("Authorization", TokenDePrueba.bearer(Map.of("oid", "oid-user", "scp", "payments.read"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("La auditoría en DynamoDB está desactivada (DYNAMODB_ENABLED=false)"));
    }

    @Test
    void tokenSinOid_401() throws Exception {
        mockMvc.perform(get("/payments/audit")
                        .header("Authorization", TokenDePrueba.bearer(Map.of("scp", "payments.read"))))
                .andExpect(status().isUnauthorized());
    }
}
