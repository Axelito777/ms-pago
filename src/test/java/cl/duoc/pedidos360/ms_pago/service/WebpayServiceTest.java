package cl.duoc.pedidos360.ms_pago.service;

import cl.duoc.pedidos360.ms_pago.config.WebpayProperties;
import cl.duoc.pedidos360.ms_pago.dto.WebpayInitRequest;
import cl.duoc.pedidos360.ms_pago.dto.WebpayInitResponse;
import cl.duoc.pedidos360.ms_pago.dto.WebpayReturnResult;
import cl.duoc.pedidos360.ms_pago.event.PaymentAttemptEvent;
import cl.duoc.pedidos360.ms_pago.event.PaymentEventPublisher;
import cl.duoc.pedidos360.ms_pago.exception.BadRequestException;
import cl.duoc.pedidos360.ms_pago.exception.WebpayGatewayException;
import cl.duoc.pedidos360.ms_pago.exception.WebpayNotConfiguredException;
import cl.duoc.pedidos360.ms_pago.model.Payment;
import cl.duoc.pedidos360.ms_pago.model.PaymentMethod;
import cl.duoc.pedidos360.ms_pago.model.PaymentStatus;
import cl.duoc.pedidos360.ms_pago.repository.PaymentRepository;
import cl.duoc.pedidos360.ms_pago.support.FakeWebpayClient;
import cl.duoc.pedidos360.ms_pago.support.RelojControlable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Lógica de Webpay con H2 real y un Transbank falso (sin red).
 * El publicador de Kafka es un mock: solo verificamos qué se publica.
 */
@DataJpaTest
@ExtendWith(OutputCaptureExtension.class)
class WebpayServiceTest {

    private static final String USER = "oid-user";
    private static final String URL_FRONT = "https://front.test/#/pago/resultado";
    private static final String LLAVE = "llave-secreta-de-prueba-579B532A";

    @Autowired
    private PaymentRepository paymentRepository;

    private FakeWebpayClient transbank;
    private WebpayProperties props;
    private PaymentEventPublisher publisher;
    private WebpayService service;

    @BeforeEach
    void setUp() {
        transbank = new FakeWebpayClient();
        props = new WebpayProperties();
        props.setCommerceCode("597055555532");
        props.setApiKey(LLAVE);
        props.setReturnUrl("https://api.test/payments/webpay/return");
        props.setFrontendResultUrl(URL_FRONT);
        publisher = mock(PaymentEventPublisher.class);
        service = new WebpayService(transbank, props, paymentRepository, publisher,
                new RelojControlable(Instant.parse("2026-11-22T12:00:00Z")));
    }

    private WebpayInitRequest request(String monto) {
        WebpayInitRequest r = new WebpayInitRequest();
        r.setOrderId(55L); // cartId del frontend
        r.setAmount(new BigDecimal(monto));
        return r; // currency queda "CLP"
    }

    private Payment pago(Long id) {
        return paymentRepository.findById(id).orElseThrow();
    }

    // ------------------------------ init ------------------------------

    @Test
    void init_creaPagoPending_yDevuelveTokenYUrl() {
        WebpayInitResponse r = service.iniciar(request("25990"), USER);

        assertThat(r.token()).isNotBlank();
        assertThat(r.url()).isEqualTo(FakeWebpayClient.URL_FORMULARIO);
        assertThat(r.buyOrder()).startsWith("P360-" + r.paymentId() + "-").hasSizeLessThanOrEqualTo(26);

        Payment p = pago(r.paymentId());
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(p.getMethod()).isEqualTo(PaymentMethod.WEBPAY);
        assertThat(p.getOrderId()).isEqualTo(55L);
        assertThat(p.getWebpayToken()).isEqualTo(r.token());
        assertThat(p.getBuyOrder()).isEqualTo(r.buyOrder());
        assertThat(p.getCardHash()).isNull();

        // A Transbank: session_id = oid, amount entero
        assertThat(transbank.ultimoSessionId).isEqualTo(USER);
        assertThat(transbank.ultimoAmount).isEqualTo(25990L);
        // Todavía no hay resultado: no se publica nada
        verify(publisher, never()).publicar(any());
    }

    @Test
    void init_buyOrderUnicoEntrePagosDistintos() {
        String a = service.iniciar(request("1000"), USER).buyOrder();
        String b = service.iniciar(request("1000"), USER).buyOrder();

        assertThat(a).isNotEqualTo(b);
        assertThat(transbank.buyOrdersRecibidos).containsExactly(a, b);
    }

    @Test
    void init_transbankFalla_502yPagoRejected() {
        transbank.fallarCreate = true;

        assertThatThrownBy(() -> service.iniciar(request("1000"), USER)).isInstanceOf(WebpayGatewayException.class);

        Payment p = paymentRepository.findAll().get(0);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(p.getWebpayToken()).isNull();
        verify(publisher).publicar(any());
    }

    @Test
    void init_sinCredenciales_503_yNoCreaPago() {
        props.setApiKey("");

        assertThatThrownBy(() -> service.iniciar(request("1000"), USER))
                .isInstanceOf(WebpayNotConfiguredException.class)
                .hasMessageContaining("WEBPAY_API_KEY");
        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    void init_montoConDecimales_400() {
        assertThatThrownBy(() -> service.iniciar(request("1000.50"), USER)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void init_montoConCerosDecimales_seAcepta() {
        service.iniciar(request("1000.00"), USER);
        assertThat(transbank.ultimoAmount).isEqualTo(1000L);
    }

    @Test
    void init_monedaDistintaDeClp_400() {
        WebpayInitRequest r = request("1000");
        r.setCurrency("USD");
        assertThatThrownBy(() -> service.iniciar(r, USER)).isInstanceOf(BadRequestException.class);
    }

    // ------------------------------ return con token_ws ------------------------------

    @Test
    void return_aprobado_APPROVED_conAuthCodeYLast4() {
        WebpayInitResponse init = service.iniciar(request("25990"), USER);

        WebpayReturnResult r = service.procesarRetorno(init.token(), null, null, null);

        assertThat(r.status()).isEqualTo("APPROVED");
        assertThat(r.redirectUrl()).isEqualTo(URL_FRONT + "?paymentId=" + init.paymentId() + "&status=APPROVED");
        Payment p = pago(init.paymentId());
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(p.getAuthorizationCode()).isEqualTo("1213");
        assertThat(p.getLast4()).isEqualTo("6623");

        ArgumentCaptor<PaymentAttemptEvent> evento = ArgumentCaptor.forClass(PaymentAttemptEvent.class);
        verify(publisher).publicar(evento.capture());
        assertThat(evento.getValue().method()).isEqualTo(PaymentMethod.WEBPAY);
        assertThat(evento.getValue().status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(evento.getValue().userId()).isEqualTo(USER);
        assertThat(evento.getValue().cardHash()).isNull();
    }

    @Test
    void return_rechazado_REJECTED() {
        WebpayInitResponse init = service.iniciar(request("25990"), USER);
        transbank.respuestaCommit = FakeWebpayClient.rechazada(new BigDecimal("25990"));

        WebpayReturnResult r = service.procesarRetorno(init.token(), null, null, null);

        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(pago(init.paymentId()).getStatus()).isEqualTo(PaymentStatus.REJECTED);
    }

    @Test
    void return_montoDistinto_REJECTED() {
        WebpayInitResponse init = service.iniciar(request("25990"), USER);
        transbank.respuestaCommit = FakeWebpayClient.aprobada(new BigDecimal("1"));

        WebpayReturnResult r = service.procesarRetorno(init.token(), null, null, null);

        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(pago(init.paymentId()).getMessage()).contains("monto");
    }

    @Test
    void return_commitFalla_REJECTED() {
        WebpayInitResponse init = service.iniciar(request("25990"), USER);
        transbank.fallarCommit = true;

        assertThat(service.procesarRetorno(init.token(), null, null, null).status()).isEqualTo("REJECTED");
    }

    @Test
    void return_repetido_noVuelveAConfirmar() {
        WebpayInitResponse init = service.iniciar(request("25990"), USER);

        service.procesarRetorno(init.token(), null, null, null);
        WebpayReturnResult segundo = service.procesarRetorno(init.token(), null, null, null);

        assertThat(transbank.commits).isEqualTo(1);
        assertThat(segundo.status()).isEqualTo("APPROVED");
        // Solo el primer retorno publica
        verify(publisher).publicar(any());
    }

    @Test
    void return_tokenDesconocido_UNKNOWN_sinCommit() {
        WebpayReturnResult r = service.procesarRetorno("token-inventado", null, null, null);

        assertThat(r.status()).isEqualTo("UNKNOWN");
        assertThat(r.paymentId()).isNull();
        assertThat(r.redirectUrl()).isEqualTo(URL_FRONT + "?paymentId=&status=UNKNOWN");
        assertThat(transbank.commits).isZero();
    }

    // ------------------------------ anulación y tiempo agotado ------------------------------

    @Test
    void return_anulado_TBK_TOKEN_CANCELLED_sinCommit() {
        WebpayInitResponse init = service.iniciar(request("25990"), USER);

        WebpayReturnResult r = service.procesarRetorno(null, init.token(), init.buyOrder(), USER);

        assertThat(r.status()).isEqualTo("CANCELLED");
        assertThat(pago(init.paymentId()).getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(transbank.commits).isZero();
        verify(publisher).publicar(any());
    }

    @Test
    void return_errorFormulario_tokenWsYTbkToken_CANCELLED_sinCommit() {
        WebpayInitResponse init = service.iniciar(request("25990"), USER);

        WebpayReturnResult r = service.procesarRetorno(init.token(), init.token(), init.buyOrder(), USER);

        assertThat(r.status()).isEqualTo("CANCELLED");
        assertThat(transbank.commits).isZero();
    }

    @Test
    void return_tiempoAgotado_CANCELLED() {
        WebpayInitResponse init = service.iniciar(request("25990"), USER);

        WebpayReturnResult r = service.procesarRetorno(null, null, init.buyOrder(), USER);

        assertThat(r.status()).isEqualTo("CANCELLED");
        assertThat(pago(init.paymentId()).getMessage()).contains("tiempo");
    }

    @Test
    void return_cancelacionDePagoYaAprobado_noLoCambia() {
        WebpayInitResponse init = service.iniciar(request("25990"), USER);
        service.procesarRetorno(init.token(), null, null, null);

        WebpayReturnResult r = service.procesarRetorno(null, null, init.buyOrder(), USER);

        assertThat(r.status()).isEqualTo("APPROVED");
    }

    @Test
    void return_sesionDeOtroUsuario_noCancela() {
        WebpayInitResponse init = service.iniciar(request("25990"), USER);

        WebpayReturnResult r = service.procesarRetorno(null, null, init.buyOrder(), "otro-usuario");

        assertThat(r.status()).isEqualTo("UNKNOWN");
        assertThat(pago(init.paymentId()).getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void return_sinUrlDeResultado_503_sinTocarElPago() {
        WebpayInitResponse init = service.iniciar(request("25990"), USER);
        props.setFrontendResultUrl("");

        assertThatThrownBy(() -> service.procesarRetorno(init.token(), null, null, null))
                .isInstanceOf(WebpayNotConfiguredException.class);
        assertThat(transbank.commits).isZero();
    }

    // ------------------------------ logs ------------------------------

    @Test
    void logs_nuncaMuestranTokenNiLlave(CapturedOutput salida) {
        WebpayInitResponse aprobado = service.iniciar(request("25990"), USER);
        service.procesarRetorno(aprobado.token(), null, null, null);
        service.procesarRetorno(aprobado.token(), null, null, null); // repetido
        WebpayInitResponse anulado = service.iniciar(request("1000"), USER);
        service.procesarRetorno(null, anulado.token(), anulado.buyOrder(), USER);
        service.procesarRetorno("token-inventado-xyz", null, null, null);

        assertThat(salida.getAll())
                .contains("pago " + aprobado.paymentId())   // se loguea el paymentId...
                .doesNotContain(aprobado.token())           // ...pero nunca el token_ws
                .doesNotContain(anulado.token())
                .doesNotContain("token-inventado-xyz")
                .doesNotContain(LLAVE);
    }

    @Test
    void toString_noIncluyeTokenNiLlave() {
        WebpayInitResponse init = service.iniciar(request("25990"), USER);

        assertThat(pago(init.paymentId()).toString()).contains("buyOrder").doesNotContain(init.token());
        assertThat(props.toString()).contains("597055555532").doesNotContain(LLAVE);
    }
}
