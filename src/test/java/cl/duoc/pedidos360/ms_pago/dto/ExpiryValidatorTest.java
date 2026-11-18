package cl.duoc.pedidos360.ms_pago.dto;

import cl.duoc.pedidos360.ms_pago.dto.validation.ExpiryValidator;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class ExpiryValidatorTest {

    // "Hoy" = 16 de noviembre de 2026
    private final ExpiryValidator validator = new ExpiryValidator(
            Clock.fixed(Instant.parse("2026-11-16T15:00:00Z"), ZoneId.of("America/Santiago")));

    @Test
    void fechaFutura_valida() {
        assertThat(validator.isValid("12/27", null)).isTrue();
    }

    @Test
    void mesActual_validaHastaFinDeMes() {
        assertThat(validator.isValid("11/26", null)).isTrue();
    }

    @Test
    void mesPasado_invalida() {
        assertThat(validator.isValid("10/26", null)).isFalse();
        assertThat(validator.isValid("12/25", null)).isFalse();
    }

    @Test
    void formatoIncorrecto_invalida() {
        assertThat(validator.isValid("13/27", null)).isFalse();
        assertThat(validator.isValid("1/27", null)).isFalse();
        assertThat(validator.isValid("12-27", null)).isFalse();
        assertThat(validator.isValid("12/2027", null)).isFalse();
    }

    @Test
    void vacio_loDelegaANotBlank() {
        assertThat(validator.isValid(null, null)).isTrue();
    }
}
