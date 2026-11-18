package cl.duoc.pedidos360.ms_pago.controller;

import cl.duoc.pedidos360.ms_pago.dto.PaymentRequest;
import cl.duoc.pedidos360.ms_pago.dto.PaymentResponse;
import cl.duoc.pedidos360.ms_pago.exception.ResourceNotFoundException;
import cl.duoc.pedidos360.ms_pago.model.PaymentStatus;
import cl.duoc.pedidos360.ms_pago.security.JwtClaims;
import cl.duoc.pedidos360.ms_pago.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Capa web aislada: PaymentService mockeado, sin BD ni Kafka */
@WebMvcTest(PaymentController.class)
@Import(JwtClaims.class)
class PaymentControllerTest {

    private static final String TARJETA = "4111111111111111";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    // ------------------------------ helpers ------------------------------

    private static String token(String payloadJson) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return "Bearer eyJhbGciOiJub25lIn0." + enc.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8)) + ".firma";
    }

    private static final String TOKEN_USER = token("{\"oid\":\"oid-user\",\"roles\":[\"User\"]}");
    private static final String TOKEN_ADMIN = token("{\"oid\":\"oid-admin\",\"roles\":[\"Admin\"]}");

    private static String body(String cardNumber, String cvv, String expiry, String amount, String userId) {
        return """
                {"orderId": 10, %s"cardNumber": "%s", "cardHolder": "Juan Perez",
                 "expiry": "%s", "cvv": "%s", "amount": %s}
                """.formatted(userId == null ? "" : "\"userId\": \"" + userId + "\", ",
                cardNumber, expiry, cvv, amount);
    }

    private static String bodyValido(String userId) {
        return body(TARJETA, "123", "12/30", "25990", userId);
    }

    private static PaymentResponse respuesta(PaymentStatus status, String userId) {
        return new PaymentResponse(1L, 10L, userId, "1111", new BigDecimal("25990"), "CLP", status,
                status == PaymentStatus.BLOCKED_FRAUD, "msg", LocalDateTime.now());
    }

    // ------------------------------ POST /payments ------------------------------

    @Test
    void aprobado_201_yUsaOidDelToken() throws Exception {
        when(paymentService.procesar(any(), eq("oid-user"))).thenReturn(respuesta(PaymentStatus.APPROVED, "oid-user"));

        mockMvc.perform(post("/payments").header("Authorization", TOKEN_USER)
                        .contentType(MediaType.APPLICATION_JSON).content(bodyValido(null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.userId").value("oid-user"))
                .andExpect(content().string(not(containsString(TARJETA))));
    }

    @Test
    void rechazado_402() throws Exception {
        when(paymentService.procesar(any(), any())).thenReturn(respuesta(PaymentStatus.REJECTED, "oid-user"));

        mockMvc.perform(post("/payments").header("Authorization", TOKEN_USER)
                        .contentType(MediaType.APPLICATION_JSON).content(bodyValido(null)))
                .andExpect(status().isPaymentRequired());
    }

    @Test
    void bloqueadoPorFraude_429() throws Exception {
        when(paymentService.procesar(any(), any())).thenReturn(respuesta(PaymentStatus.BLOCKED_FRAUD, "oid-user"));

        mockMvc.perform(post("/payments").header("Authorization", TOKEN_USER)
                        .contentType(MediaType.APPLICATION_JSON).content(bodyValido(null)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.fraudSuspected").value(true));
    }

    @Test
    void sinToken_usaUserIdDelBody() throws Exception {
        when(paymentService.procesar(any(), eq("user-body"))).thenReturn(respuesta(PaymentStatus.APPROVED, "user-body"));

        mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(bodyValido("user-body")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value("user-body"));
    }

    @Test
    void sinTokenNiUserId_400() throws Exception {
        mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(bodyValido(null)))
                .andExpect(status().isBadRequest());
        verify(paymentService, never()).procesar(any(), any());
    }

    @Test
    void userIdDistintoAlOid_sinAdmin_403_yNoCuentaComoIntento() throws Exception {
        mockMvc.perform(post("/payments").header("Authorization", TOKEN_USER)
                        .contentType(MediaType.APPLICATION_JSON).content(bodyValido("otro-usuario")))
                .andExpect(status().isForbidden());
        verify(paymentService, never()).procesar(any(), any());
    }

    @Test
    void userIdDistintoAlOid_conAdmin_seAcepta() throws Exception {
        when(paymentService.procesar(any(), eq("otro-usuario")))
                .thenReturn(respuesta(PaymentStatus.APPROVED, "otro-usuario"));

        mockMvc.perform(post("/payments").header("Authorization", TOKEN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(bodyValido("otro-usuario")))
                .andExpect(status().isCreated());
    }

    @Test
    void validacion_400_noLlamaAlService_niDevuelveLaTarjeta() throws Exception {
        String malo = body("41111", "12", "01/20", "0", null); // tarjeta corta, cvv corto, vencida, monto 0

        mockMvc.perform(post("/payments").header("Authorization", TOKEN_USER)
                        .contentType(MediaType.APPLICATION_JSON).content(malo))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details", hasItem("cardNumber debe tener entre 13 y 19 dígitos, sin espacios")))
                .andExpect(jsonPath("$.details", hasItem("cvv debe tener 3 o 4 dígitos")))
                .andExpect(jsonPath("$.details", hasItem("expiry debe tener formato MM/YY y no estar vencida")))
                .andExpect(jsonPath("$.details", hasItem("amount debe ser mayor que 0")))
                .andExpect(content().string(not(containsString("41111"))));

        // Los 400 no cuentan en la ventana antifraude: el service ni se llama
        verify(paymentService, never()).procesar(any(), any());
    }

    @Test
    void tokenIlegible_401() throws Exception {
        mockMvc.perform(post("/payments").header("Authorization", "Bearer basura")
                        .contentType(MediaType.APPLICATION_JSON).content(bodyValido(null)))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------ GET ------------------------------

    @Test
    void listarSinUserId_usaOid() throws Exception {
        when(paymentService.listarPorUsuario("oid-user")).thenReturn(List.of(respuesta(PaymentStatus.APPROVED, "oid-user")));

        mockMvc.perform(get("/payments").header("Authorization", TOKEN_USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value("oid-user"));
    }

    @Test
    void listarSinUserIdNiToken_400() throws Exception {
        mockMvc.perform(get("/payments")).andExpect(status().isBadRequest());
    }

    @Test
    void listarOtroUsuario_sinAdmin_403() throws Exception {
        mockMvc.perform(get("/payments").param("userId", "otro").header("Authorization", TOKEN_USER))
                .andExpect(status().isForbidden());
    }

    @Test
    void listarOtroUsuario_conAdmin_200() throws Exception {
        when(paymentService.listarPorUsuario("otro")).thenReturn(List.of());

        mockMvc.perform(get("/payments").param("userId", "otro").header("Authorization", TOKEN_ADMIN))
                .andExpect(status().isOk());
    }

    @Test
    void buscarPorId_inexistente_404() throws Exception {
        when(paymentService.buscarPorId(99L)).thenThrow(new ResourceNotFoundException("No existe el pago 99"));

        mockMvc.perform(get("/payments/99").header("Authorization", TOKEN_USER))
                .andExpect(status().isNotFound());
    }

    @Test
    void buscarPorId_deOtroUsuario_403() throws Exception {
        when(paymentService.buscarPorId(1L)).thenReturn(respuesta(PaymentStatus.APPROVED, "otro"));

        mockMvc.perform(get("/payments/1").header("Authorization", TOKEN_USER))
                .andExpect(status().isForbidden());
    }

    @Test
    void fraudAlerts_soloAdmin() throws Exception {
        when(paymentService.listarAlertas()).thenReturn(List.of());

        mockMvc.perform(get("/payments/fraud-alerts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/payments/fraud-alerts").header("Authorization", TOKEN_USER))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/payments/fraud-alerts").header("Authorization", token("{\"oid\":\"x\"}")))
                .andExpect(status().isForbidden()); // sin claim roles = lista vacía
        mockMvc.perform(get("/payments/fraud-alerts").header("Authorization", TOKEN_ADMIN))
                .andExpect(status().isOk());
    }
}
