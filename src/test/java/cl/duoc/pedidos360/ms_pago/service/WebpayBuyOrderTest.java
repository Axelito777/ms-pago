package cl.duoc.pedidos360.ms_pago.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** buy_order de Transbank: máximo 26 caracteres y único entre pagos distintos */
class WebpayBuyOrderTest {

    private static final Instant AHORA = Instant.parse("2026-11-22T12:00:00Z");

    @Test
    void formato_P360_id_milisegundosBase36() {
        assertThat(WebpayService.generarBuyOrder(42L, AHORA))
                .isEqualTo("P360-42-" + Long.toString(AHORA.toEpochMilli(), 36));
    }

    @Test
    void nuncaSuperaLos26Caracteres_niConElIdMasGrande() {
        for (long id : new long[]{1L, 999_999L, 1_000_000_000_000L, Long.MAX_VALUE}) {
            // También con una fecha muy lejana (más dígitos en base 36)
            for (Instant instante : new Instant[]{AHORA, Instant.parse("9999-12-31T23:59:59Z")}) {
                assertThat(WebpayService.generarBuyOrder(id, instante)).hasSizeLessThanOrEqualTo(26);
            }
        }
    }

    @Test
    void esUnicoEntrePagosDistintos_aunqueSeanDelMismoMilisegundo() {
        Set<String> generados = new HashSet<>();
        for (long id = 1; id <= 10_000; id++) {
            generados.add(WebpayService.generarBuyOrder(id, AHORA));
        }
        assertThat(generados).hasSize(10_000);
    }

    @Test
    void conIdEnorme_elRecorteConservaElIdCompleto() {
        // Si el recorte se come los milisegundos, el id (que es único) sigue entero
        String buyOrder = WebpayService.generarBuyOrder(Long.MAX_VALUE, AHORA);
        assertThat(buyOrder).hasSize(26).startsWith("P360-" + Long.MAX_VALUE + "-");
        assertThat(WebpayService.generarBuyOrder(Long.MAX_VALUE - 1, AHORA)).isNotEqualTo(buyOrder);
    }
}
