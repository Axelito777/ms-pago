package cl.duoc.pedidos360.ms_pago.controller;

import cl.duoc.pedidos360.ms_pago.config.SecurityConfig;
import cl.duoc.pedidos360.ms_pago.support.TokenDePrueba;
import cl.duoc.pedidos360.ms_pago.security.JwtClaims;
import cl.duoc.pedidos360.ms_pago.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Seguridad de PaymentControllerSecurityTest sin red:
 * - Reglas de scope con tokens firmados (el controller/interceptor también lee el header Authorization).
 * - Tokens firmados con la llave RSA de prueba: validación real (firma, emisor, audiencia, expiración).
 */
@WebMvcTest(PaymentController.class)
@Import({JwtClaims.class, SecurityConfig.class})
class PaymentControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    /** Reemplaza el decoder de Azure (JWKS por internet) por uno con la llave local + mismos validadores */
    @TestBean
    private JwtDecoder jwtDecoder;

    static JwtDecoder jwtDecoder() {
        return TokenDePrueba.decoder();
    }

    /** Petición a un endpoint protegido y claims que la autorizan (para las pruebas de validación) */
    private static MockHttpServletRequestBuilder peticionValida() {
        return get("/payments");
    }

    private static final Map<String, Object> CLAIMS_VALIDOS = Map.of("oid", "oid-user", "scp", "payments.read");

    // ---------- Reglas por ruta ----------

    @Test
    void sinToken_401() throws Exception {
        mockMvc.perform(get("/payments"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void scopeRead_listar_200() throws Exception {
        when(paymentService.listarPorUsuario("oid-user")).thenReturn(List.of());
        mockMvc.perform(get("/payments").header("Authorization", bearer(Map.of("oid", "oid-user", "scp", "payments.read"))))
                .andExpect(status().isOk());
    }

    @Test
    void scopeRead_fraudAlerts_conAdmin_200() throws Exception {
        when(paymentService.listarAlertas()).thenReturn(List.of());
        mockMvc.perform(get("/payments/fraud-alerts").header("Authorization",
                        bearer(Map.of("oid", "oid-admin", "roles", List.of("Admin"), "scp", "payments.read"))))
                .andExpect(status().isOk());
    }

    @Test
    void scopeRead_fraudAlerts_sinAdmin_403_reglaDeJwtClaims() throws Exception {
        mockMvc.perform(get("/payments/fraud-alerts").header("Authorization",
                        bearer(Map.of("oid", "oid-user", "roles", List.of("User"), "scp", "payments.read"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void scopeRead_noPuedePagar_403() throws Exception {
        mockMvc.perform(post("/payments").header("Authorization", bearer(Map.of("oid", "oid-user", "scp", "payments.read")))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void soloScopeWrite_noPuedeListar_403() throws Exception {
        mockMvc.perform(get("/payments").header("Authorization", bearer(Map.of("oid", "oid-user", "scp", "payments.write"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void scopeDeOtroServicio_403() throws Exception {
        mockMvc.perform(get("/payments/1").header("Authorization", bearer(Map.of("oid", "oid-user", "scp", "orders.read"))))
                .andExpect(status().isForbidden());
    }


    // ---------- Validación real del token (firma, emisor, audiencia, expiración) ----------

    @Test
    void tokenValido_audienciaApi_2xx() throws Exception {
        mockMvc.perform(conToken(TokenDePrueba.LLAVE_AZURE, TokenDePrueba.EMISOR, "api://" + TokenDePrueba.CLIENT_ID, Instant.now().plusSeconds(600)))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void tokenValido_audienciaSinPrefijo_2xx() throws Exception {
        mockMvc.perform(conToken(TokenDePrueba.LLAVE_AZURE, TokenDePrueba.EMISOR, TokenDePrueba.CLIENT_ID, Instant.now().plusSeconds(600)))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void tokenExpirado_401() throws Exception {
        // 5 min en el pasado: supera los 60 s de tolerancia de reloj
        mockMvc.perform(conToken(TokenDePrueba.LLAVE_AZURE, TokenDePrueba.EMISOR, TokenDePrueba.CLIENT_ID, Instant.now().minusSeconds(300)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenDeOtraAudiencia_401() throws Exception {
        mockMvc.perform(conToken(TokenDePrueba.LLAVE_AZURE, TokenDePrueba.EMISOR, "api://otra-app", Instant.now().plusSeconds(600)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenDeOtroEmisor_401() throws Exception {
        mockMvc.perform(conToken(TokenDePrueba.LLAVE_AZURE, "https://login.microsoftonline.com/otro-tenant/v2.0",
                        TokenDePrueba.CLIENT_ID, Instant.now().plusSeconds(600)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenConFirmaFalsa_401() throws Exception {
        mockMvc.perform(conToken(TokenDePrueba.LLAVE_FALSA, TokenDePrueba.EMISOR, TokenDePrueba.CLIENT_ID, Instant.now().plusSeconds(600)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenBasura_401() throws Exception {
        mockMvc.perform(peticionValida().header("Authorization", "Bearer no-es-un-jwt"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- helpers ----------

    private MockHttpServletRequestBuilder conToken(java.security.KeyPair llave, String emisor, String audiencia, Instant expira) {
        return peticionValida().header("Authorization",
                "Bearer " + TokenDePrueba.firmar(llave, emisor, audiencia, expira, CLAIMS_VALIDOS));
    }

    /** Header Authorization firmado (para endpoints que además leen claims del header) */
    private static String bearer(Map<String, Object> claims) {
        return TokenDePrueba.bearer(claims);
    }
}
