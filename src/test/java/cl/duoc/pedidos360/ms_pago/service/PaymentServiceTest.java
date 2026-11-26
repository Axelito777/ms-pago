package cl.duoc.pedidos360.ms_pago.service;

import cl.duoc.pedidos360.ms_pago.config.FraudProperties;
import cl.duoc.pedidos360.ms_pago.dto.PaymentRequest;
import cl.duoc.pedidos360.ms_pago.dto.PaymentResponse;
import cl.duoc.pedidos360.ms_pago.event.PaymentAttemptEvent;
import cl.duoc.pedidos360.ms_pago.event.PaymentEventPublisher;
import cl.duoc.pedidos360.ms_pago.exception.UserBlockedException;
import cl.duoc.pedidos360.ms_pago.model.FraudAlert;
import cl.duoc.pedidos360.ms_pago.model.Payment;
import cl.duoc.pedidos360.ms_pago.model.PaymentStatus;
import cl.duoc.pedidos360.ms_pago.repository.FraudAlertRepository;
import cl.duoc.pedidos360.ms_pago.repository.PaymentRepository;
import cl.duoc.pedidos360.ms_pago.support.RelojControlable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final String TARJETA = "4111111111111111";

    @Mock private PaymentGateway gateway;
    @Mock private PaymentRepository paymentRepository;
    @Mock private FraudAlertRepository fraudAlertRepository;
    @Mock private PaymentEventPublisher publisher;

    private PaymentService service;
    private UserBlockService userBlockService;
    private RelojControlable reloj;

    @BeforeEach
    void setUp() {
        RelojControlable reloj = new RelojControlable(Instant.parse("2026-11-16T12:00:00Z"));
        FraudProperties props = new FraudProperties();
        this.reloj = reloj;
        FraudDetectionService fraude = new FraudDetectionService(reloj, props);
        userBlockService = new UserBlockService(reloj, props, fraude);
        service = new PaymentService(new CardDataService(), fraude, userBlockService, gateway,
                paymentRepository, fraudAlertRepository, publisher, props, reloj);
        // El repositorio "asigna" id 1 al guardar
        // lenient: no todos los tests guardan pagos
        org.mockito.Mockito.lenient().when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(1L);
            return p;
        });
    }

    private PaymentRequest request(String tarjeta) {
        PaymentRequest r = new PaymentRequest();
        r.setOrderId(10L);
        r.setCardNumber(tarjeta);
        r.setCardHolder("Juan Perez");
        r.setExpiry("12/30");
        r.setCvv("123");
        r.setAmount(new BigDecimal("25990"));
        return r; // currency queda "CLP" por defecto
    }

    @Test
    void aprobado_guardaSoloHashYLast4_yPublicaEvento() {
        when(gateway.procesar(any(), any(), any())).thenReturn(new PaymentGateway.GatewayResult(true, "ok"));

        PaymentResponse r = service.procesar(request(TARJETA), "user-1");

        assertThat(r.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(r.last4()).isEqualTo("1111");

        ArgumentCaptor<Payment> pago = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(pago.capture());
        assertThat(pago.getValue().getCardHash()).hasSize(64);
        assertThat(pago.getValue().toString()).doesNotContain(TARJETA).doesNotContain("cvv");

        ArgumentCaptor<PaymentAttemptEvent> evento = ArgumentCaptor.forClass(PaymentAttemptEvent.class);
        verify(publisher).publicar(evento.capture());
        assertThat(evento.getValue().status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(evento.getValue().cardHash()).isEqualTo(pago.getValue().getCardHash());
        assertThat(evento.getValue().eventId()).isNotBlank();
        assertThat(evento.getValue().toString()).doesNotContain(TARJETA);
    }

    @Test
    void rechazado_porPasarela() {
        when(gateway.procesar(any(), any(), any())).thenReturn(new PaymentGateway.GatewayResult(false, "rechazado"));

        PaymentResponse r = service.procesar(request("4000000000000000"), "user-1");

        assertThat(r.status()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(r.fraudSuspected()).isFalse();
        verify(publisher).publicar(any());
    }

    @Test
    void cuartoIntento_bloqueado_creaAlerta_noLlamaPasarela_yPublica() {
        when(gateway.procesar(any(), any(), any())).thenReturn(new PaymentGateway.GatewayResult(true, "ok"));
        for (int i = 0; i < 3; i++) {
            service.procesar(request(TARJETA), "user-1");
        }

        PaymentResponse cuarto = service.procesar(request(TARJETA), "user-1");

        assertThat(cuarto.status()).isEqualTo(PaymentStatus.BLOCKED_FRAUD);
        assertThat(cuarto.fraudSuspected()).isTrue();
        verify(gateway, org.mockito.Mockito.times(3)).procesar(any(), any(), any()); // el 4.º no llega

        ArgumentCaptor<FraudAlert> alerta = ArgumentCaptor.forClass(FraudAlert.class);
        verify(fraudAlertRepository).save(alerta.capture());
        assertThat(alerta.getValue().getCantidadIntentos()).isEqualTo(4);
        assertThat(alerta.getValue().getLast4()).isEqualTo("1111");
        assertThat(alerta.getValue().getUserId()).isEqualTo("user-1");

        verify(publisher, org.mockito.Mockito.times(4)).publicar(any());
    }

    @Test
    void sinBloqueo_noCreaAlerta() {
        when(gateway.procesar(any(), any(), any())).thenReturn(new PaymentGateway.GatewayResult(true, "ok"));

        service.procesar(request(TARJETA), "user-1");

        verify(fraudAlertRepository, never()).save(any());
    }

    @Test
    void toStringDelRequest_noIncluyeTarjetaNiCvv() {
        String texto = request(TARJETA).toString();

        assertThat(texto).doesNotContain(TARJETA).doesNotContain("cardNumber").doesNotContain("cvv");
    }

    // ------------------------- Bloqueo del USUARIO -------------------------

    private static final String[] TARJETAS = {
            "4111111111111111", "5555555555554444", "4012888888881881", "378282246310005", "6011111111111117"};

    /** 3 pagos aprobados del usuario, cada uno con una tarjeta distinta */
    private void tresAprobadosConTarjetasDistintas(String userId) {
        for (int i = 0; i < 3; i++) {
            assertThat(service.procesar(request(TARJETAS[i]), userId).status()).isEqualTo(PaymentStatus.APPROVED);
        }
    }

    @Test
    void cuatroIntentosConTarjetasDistintas_elCuarto429_yElQuinto423() {
        when(gateway.procesar(any(), any(), any())).thenReturn(new PaymentGateway.GatewayResult(true, "ok"));
        tresAprobadosConTarjetasDistintas("user-1");

        // 4.º con otra tarjeta: la tarjeta es nueva, pero el usuario ya suma 4 en la ventana
        PaymentResponse cuarto = service.procesar(request(TARJETAS[3]), "user-1");
        assertThat(cuarto.status()).isEqualTo(PaymentStatus.BLOCKED_FRAUD);

        Instant hasta = reloj.instant().plus(Duration.ofMinutes(10));
        assertThat(userBlockService.bloqueadoHasta("user-1")).contains(hasta);

        // La alerta y el evento del 4.º llevan blockedUntil (es el evento de bloqueo)
        ArgumentCaptor<FraudAlert> alerta = ArgumentCaptor.forClass(FraudAlert.class);
        verify(fraudAlertRepository).save(alerta.capture());
        assertThat(alerta.getValue().getBlockedUntil()).isEqualTo(hasta);
        ArgumentCaptor<PaymentAttemptEvent> eventos = ArgumentCaptor.forClass(PaymentAttemptEvent.class);
        verify(publisher, times(4)).publicar(eventos.capture());
        assertThat(eventos.getAllValues().get(3).blockedUntil()).isEqualTo(hasta);
        assertThat(eventos.getAllValues().subList(0, 3)).allMatch(e -> e.blockedUntil() == null);

        // 5.º, con la tarjeta que quede: 423
        assertThatThrownBy(() -> service.procesar(request(TARJETAS[4]), "user-1"))
                .isInstanceOf(UserBlockedException.class)
                .extracting("until").isEqualTo(hasta);
    }

    @Test
    void usuarioBloqueado_noLlamaPasarela_noGuarda_niPublica() {
        userBlockService.bloquear("user-1");

        assertThatThrownBy(() -> service.procesar(request(TARJETA), "user-1"))
                .isInstanceOf(UserBlockedException.class);

        verify(gateway, never()).procesar(any(), any(), any());
        verify(paymentRepository, never()).save(any());
        verify(publisher, never()).publicar(any());
    }

    @Test
    void despuesDelDesbloqueo_vuelveAFuncionar() {
        when(gateway.procesar(any(), any(), any())).thenReturn(new PaymentGateway.GatewayResult(true, "ok"));
        tresAprobadosConTarjetasDistintas("user-1");
        service.procesar(request(TARJETAS[3]), "user-1"); // 429 y bloqueo

        assertThat(userBlockService.desbloquear("user-1")).isTrue();

        // El desbloqueo también borra el conteo del usuario: el siguiente pago se aprueba al tiro
        assertThat(service.procesar(request(TARJETAS[4]), "user-1").status()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void despuesDeLaExpiracion_vuelveAFuncionar() {
        when(gateway.procesar(any(), any(), any())).thenReturn(new PaymentGateway.GatewayResult(true, "ok"));
        tresAprobadosConTarjetasDistintas("user-1");
        service.procesar(request(TARJETAS[3]), "user-1"); // 429 y bloqueo

        reloj.avanzar(Duration.ofMinutes(10).plusSeconds(1));

        assertThat(userBlockService.bloqueadoHasta("user-1")).isEmpty();
        assertThat(service.procesar(request(TARJETAS[4]), "user-1").status()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void otroUsuario_noSeVeAfectado() {
        when(gateway.procesar(any(), any(), any())).thenReturn(new PaymentGateway.GatewayResult(true, "ok"));
        tresAprobadosConTarjetasDistintas("user-1");
        service.procesar(request(TARJETAS[3]), "user-1"); // bloquea a user-1

        assertThat(service.procesar(request(TARJETAS[4]), "user-2").status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(userBlockService.bloqueadoHasta("user-2")).isEmpty();
    }
}
