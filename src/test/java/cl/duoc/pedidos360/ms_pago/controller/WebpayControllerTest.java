package cl.duoc.pedidos360.ms_pago.controller;

import cl.duoc.pedidos360.ms_pago.config.SecurityConfig;
import cl.duoc.pedidos360.ms_pago.dto.WebpayInitResponse;
import cl.duoc.pedidos360.ms_pago.dto.WebpayReturnResult;
import cl.duoc.pedidos360.ms_pago.exception.WebpayGatewayException;
import cl.duoc.pedidos360.ms_pago.exception.WebpayNotConfiguredException;
import cl.duoc.pedidos360.ms_pago.security.JwtClaims;
import cl.duoc.pedidos360.ms_pago.service.PaymentService;
import cl.duoc.pedidos360.ms_pago.service.WebpayService;
import cl.duoc.pedidos360.ms_pago.support.TokenDePrueba;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Capa web + seguridad de Webpay: WebpayService mockeado, tokens firmados con la llave de prueba */
@WebMvcTest({WebpayController.class, PaymentController.class})
@Import({JwtClaims.class, SecurityConfig.class})
class WebpayControllerTest {

    private static final String BODY = "{\"orderId\": 55, \"amount\": 25990, \"currency\": \"CLP\"}";
    private static final String URL_FRONT = "https://front.test/#/pago/resultado";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WebpayService webpayService;

    @MockitoBean
    private PaymentService paymentService;

    @TestBean
    private JwtDecoder jwtDecoder;

    static JwtDecoder jwtDecoder() {
        return TokenDePrueba.decoder();
    }

    private static String bearer(String scope) {
        return TokenDePrueba.bearer(Map.of("oid", "oid-user", "scp", scope));
    }

    // ------------------------------ init ------------------------------

    @Test
    void init_conScopeWrite_201_conTokenYUrl() throws Exception {
        when(webpayService.iniciar(any(), eq("oid-user")))
                .thenReturn(new WebpayInitResponse(7L, "tok", "https://webpay/init", "P360-7-abc"));

        mockMvc.perform(post("/payments/webpay/init").header("Authorization", bearer("payments.write"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").value(7))
                .andExpect(jsonPath("$.token").value("tok"))
                .andExpect(jsonPath("$.url").value("https://webpay/init"))
                .andExpect(jsonPath("$.buyOrder").value("P360-7-abc"));
    }

    @Test
    void init_sinToken_401() throws Exception {
        mockMvc.perform(post("/payments/webpay/init").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(webpayService);
    }

    @Test
    void init_soloScopeRead_403() throws Exception {
        mockMvc.perform(post("/payments/webpay/init").header("Authorization", bearer("payments.read"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(webpayService);
    }

    @Test
    void init_bodyInvalido_400() throws Exception {
        mockMvc.perform(post("/payments/webpay/init").header("Authorization", bearer("payments.write"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\": -1, \"amount\": 0}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(webpayService);
    }

    @Test
    void init_transbankFalla_502() throws Exception {
        when(webpayService.iniciar(any(), any())).thenThrow(new WebpayGatewayException("No se pudo comunicar con Transbank"));

        mockMvc.perform(post("/payments/webpay/init").header("Authorization", bearer("payments.write"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadGateway());
    }

    @Test
    void init_sinConfigurar_503() throws Exception {
        when(webpayService.iniciar(any(), any())).thenThrow(new WebpayNotConfiguredException("Webpay no está configurado"));

        mockMvc.perform(post("/payments/webpay/init").header("Authorization", bearer("payments.write"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Webpay no está configurado"));
    }

    // ------------------------------ return (público) ------------------------------

    @Test
    void return_POSTsinToken_302alFrontend() throws Exception {
        when(webpayService.procesarRetorno("tok", null, null, null))
                .thenReturn(new WebpayReturnResult(7L, "APPROVED", URL_FRONT + "?paymentId=7&status=APPROVED"));

        mockMvc.perform(post("/payments/webpay/return")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("token_ws=tok"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", URL_FRONT + "?paymentId=7&status=APPROVED"));
    }

    @Test
    void return_GETsinToken_302() throws Exception {
        when(webpayService.procesarRetorno("tok", null, null, null))
                .thenReturn(new WebpayReturnResult(7L, "REJECTED", URL_FRONT + "?paymentId=7&status=REJECTED"));

        mockMvc.perform(get("/payments/webpay/return").param("token_ws", "tok"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", URL_FRONT + "?paymentId=7&status=REJECTED"));
    }

    @Test
    void return_anulacion_pasaLosParametrosTBK() throws Exception {
        when(webpayService.procesarRetorno(null, "tbk", "P360-7-abc", "oid-user"))
                .thenReturn(new WebpayReturnResult(7L, "CANCELLED", URL_FRONT + "?paymentId=7&status=CANCELLED"));

        mockMvc.perform(post("/payments/webpay/return").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("TBK_TOKEN=tbk&TBK_ORDEN_COMPRA=P360-7-abc&TBK_ID_SESION=oid-user"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", URL_FRONT + "?paymentId=7&status=CANCELLED"));
    }

    @Test
    void return_aceptaTBK_ID_SESSIONconDosS() throws Exception {
        when(webpayService.procesarRetorno(isNull(), isNull(), eq("P360-7-abc"), eq("oid-user")))
                .thenReturn(new WebpayReturnResult(7L, "CANCELLED", URL_FRONT + "?paymentId=7&status=CANCELLED"));

        mockMvc.perform(get("/payments/webpay/return")
                        .param("TBK_ORDEN_COMPRA", "P360-7-abc").param("TBK_ID_SESSION", "oid-user"))
                .andExpect(status().isFound());
        verify(webpayService).procesarRetorno(isNull(), isNull(), eq("P360-7-abc"), eq("oid-user"));
    }

    @Test
    void return_pagoNoEncontrado_302conUNKNOWN() throws Exception {
        when(webpayService.procesarRetorno(any(), any(), any(), any()))
                .thenReturn(new WebpayReturnResult(null, "UNKNOWN", URL_FRONT + "?paymentId=&status=UNKNOWN"));

        mockMvc.perform(get("/payments/webpay/return").param("token_ws", "inventado"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", URL_FRONT + "?paymentId=&status=UNKNOWN"));
    }

    // ------------------------------ lo demás sigue protegido ------------------------------

    @Test
    void otrasRutasDeWebpay_siguenBloqueadas() throws Exception {
        // Solo /return es público; una ruta vecina sin token responde 401
        mockMvc.perform(get("/payments/webpay/otra")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/payments/webpay/init")).andExpect(status().isUnauthorized());
    }

    @Test
    void rutasDePagos_sinToken_401() throws Exception {
        mockMvc.perform(get("/payments")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/payments/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/payments/fraud-alerts")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verify(paymentService, never()).procesar(any(), any());
    }
}
