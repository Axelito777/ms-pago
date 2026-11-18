package cl.duoc.pedidos360.ms_pago.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedPaymentGatewayTest {

    private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway();

    @Test
    void apruebaTarjetaNormal() {
        assertThat(gateway.procesar("1111", new BigDecimal("15000"), "CLP").aprobado()).isTrue();
    }

    @Test
    void rechazaTarjetaTerminadaEn0000() {
        assertThat(gateway.procesar("0000", new BigDecimal("15000"), "CLP").aprobado()).isFalse();
    }
}
