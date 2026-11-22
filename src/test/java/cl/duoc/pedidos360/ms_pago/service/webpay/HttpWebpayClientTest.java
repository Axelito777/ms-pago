package cl.duoc.pedidos360.ms_pago.service.webpay;

import cl.duoc.pedidos360.ms_pago.config.WebpayProperties;
import cl.duoc.pedidos360.ms_pago.exception.WebpayGatewayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifica rutas, headers y JSON que se envían a Transbank, SIN red:
 * MockRestServiceServer intercepta el RestClient y responde lo que le digamos.
 */
@ExtendWith(OutputCaptureExtension.class)
class HttpWebpayClientTest {

    private static final String BASE = "https://webpay3gint.transbank.cl";
    private static final String RUTA = BASE + "/rswebpaytransaction/api/webpay/v1.2/transactions";
    private static final String LLAVE = "llave-secreta-de-prueba";
    private static final String TOKEN = "01abcdef0123456789abcdef0123456789abcdef0123456789abcdef01234567";

    private MockRestServiceServer transbank;
    private HttpWebpayClient client;

    @BeforeEach
    void setUp() {
        WebpayProperties props = new WebpayProperties();
        props.setCommerceCode("597055555532");
        props.setApiKey(LLAVE);
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        transbank = MockRestServiceServer.bindTo(builder).build();
        client = new HttpWebpayClient(builder.build(), props);
    }

    @Test
    void create_POSTconHeadersYBodySnakeCase() {
        transbank.expect(requestTo(RUTA))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Tbk-Api-Key-Id", "597055555532"))
                .andExpect(header("Tbk-Api-Key-Secret", LLAVE))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().json("""
                        {"buy_order":"P360-1-abc","session_id":"oid-user","amount":25990,
                         "return_url":"https://api.test/payments/webpay/return"}
                        """, true))
                .andRespond(withSuccess("""
                        {"token":"%s","url":"https://webpay3gint.transbank.cl/webpayserver/initTransaction"}
                        """.formatted(TOKEN), MediaType.APPLICATION_JSON));

        WebpayCreateResponse r = client.create("P360-1-abc", "oid-user", 25990, "https://api.test/payments/webpay/return");

        assertThat(r.token()).isEqualTo(TOKEN);
        assertThat(r.url()).endsWith("/initTransaction");
        transbank.verify();
    }

    @Test
    void commit_PUTconTokenEnLaUrl_yLeeLaRespuesta() {
        transbank.expect(requestTo(RUTA + "/" + TOKEN))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("Tbk-Api-Key-Id", "597055555532"))
                .andExpect(header("Tbk-Api-Key-Secret", LLAVE))
                .andRespond(withSuccess("""
                        {"vci":"TSY","amount":25990,"status":"AUTHORIZED","buy_order":"P360-1-abc",
                         "session_id":"oid-user","card_detail":{"card_number":"6623"},
                         "accounting_date":"1122","transaction_date":"2026-11-22T12:00:00.000Z",
                         "authorization_code":"1213","payment_type_code":"VN","response_code":0,
                         "installments_number":0}
                        """, MediaType.APPLICATION_JSON));

        WebpayCommitResponse r = client.commit(TOKEN);

        assertThat(r.autorizada()).isTrue();
        assertThat(r.amount()).isEqualByComparingTo(new BigDecimal("25990"));
        assertThat(r.authorizationCode()).isEqualTo("1213");
        assertThat(r.last4()).isEqualTo("6623");
        transbank.verify();
    }

    @Test
    void commit_rechazado_noEsAutorizada() {
        transbank.expect(requestTo(RUTA + "/" + TOKEN))
                .andRespond(withSuccess("""
                        {"amount":25990,"status":"FAILED","response_code":-1,"card_detail":{"card_number":"0568"}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.commit(TOKEN).autorizada()).isFalse();
    }

    @Test
    void errorHttp_seTraduceA502_sinLoguearTokenNiLlave(CapturedOutput salida) {
        transbank.expect(requestTo(RUTA + "/" + TOKEN))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                        .body("{\"error_message\":\"Invalid status '1' for transaction while authorizing\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.commit(TOKEN))
                .isInstanceOf(WebpayGatewayException.class)
                .hasMessageContaining("422")
                .hasMessageNotContaining(TOKEN);
        assertThat(salida.getAll()).contains("422").doesNotContain(TOKEN).doesNotContain(LLAVE);
    }

    @Test
    void createSinToken_502() {
        transbank.expect(requestTo(RUTA))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.create("bo", "s", 1, "r")).isInstanceOf(WebpayGatewayException.class);
    }
}
