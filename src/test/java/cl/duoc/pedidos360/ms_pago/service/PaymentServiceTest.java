package cl.duoc.pedidos360.ms_pago.service;

import cl.duoc.pedidos360.ms_pago.config.FraudProperties;
import cl.duoc.pedidos360.ms_pago.dto.PaymentRequest;
import cl.duoc.pedidos360.ms_pago.dto.PaymentResponse;
import cl.duoc.pedidos360.ms_pago.event.PaymentAttemptEvent;
import cl.duoc.pedidos360.ms_pago.event.PaymentEventPublisher;
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
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
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

    @BeforeEach
    void setUp() {
        RelojControlable reloj = new RelojControlable(Instant.parse("2026-11-16T12:00:00Z"));
        FraudProperties props = new FraudProperties();
        service = new PaymentService(new CardDataService(), new FraudDetectionService(reloj, props), gateway,
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
}
