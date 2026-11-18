package cl.duoc.pedidos360.ms_pago.service;

import cl.duoc.pedidos360.ms_pago.config.FraudProperties;
import cl.duoc.pedidos360.ms_pago.support.RelojControlable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Ventana deslizante: máximo 3 intentos en 10 s por tarjeta (valores por defecto) */
class FraudDetectionServiceTest {

    private static final String TARJETA_A = "hash-tarjeta-a";
    private static final String TARJETA_B = "hash-tarjeta-b";

    private RelojControlable reloj;
    private FraudDetectionService service;

    @BeforeEach
    void setUp() {
        reloj = new RelojControlable(Instant.parse("2026-11-16T12:00:00Z"));
        FraudProperties props = new FraudProperties(); // 10 s, máximo 3
        service = new FraudDetectionService(reloj, props);
    }

    @Test
    void primerIntento_permitido() {
        FraudCheckResult r = service.registrarIntento(TARJETA_A);

        assertThat(r.bloqueado()).isFalse();
        assertThat(r.intentosEnVentana()).isEqualTo(1);
    }

    @Test
    void tercerIntento_permitido() {
        service.registrarIntento(TARJETA_A);
        reloj.avanzar(Duration.ofSeconds(2));
        service.registrarIntento(TARJETA_A);
        reloj.avanzar(Duration.ofSeconds(2));
        FraudCheckResult tercero = service.registrarIntento(TARJETA_A);

        assertThat(tercero.bloqueado()).isFalse();
        assertThat(tercero.intentosEnVentana()).isEqualTo(3);
    }

    @Test
    void cuartoIntentoDentroDe10s_bloqueado() {
        for (int i = 0; i < 3; i++) {
            service.registrarIntento(TARJETA_A);
            reloj.avanzar(Duration.ofSeconds(3));
        }
        // Han pasado 9 s desde el 1.º: el 4.º cae dentro de la ventana
        FraudCheckResult cuarto = service.registrarIntento(TARJETA_A);

        assertThat(cuarto.bloqueado()).isTrue();
        assertThat(cuarto.intentosEnVentana()).isEqualTo(4);
    }

    @Test
    void losBloqueadosTambienCuentan_quintoSigueBloqueado() {
        for (int i = 0; i < 4; i++) {
            service.registrarIntento(TARJETA_A);
        }
        FraudCheckResult quinto = service.registrarIntento(TARJETA_A);

        assertThat(quinto.bloqueado()).isTrue();
        assertThat(quinto.intentosEnVentana()).isEqualTo(5);
    }

    @Test
    void intentosSeparadosPorMasDe10s_noCuentanJuntos() {
        for (int i = 0; i < 3; i++) {
            service.registrarIntento(TARJETA_A);
        }
        reloj.avanzar(Duration.ofSeconds(11)); // los 3 anteriores salen de la ventana

        FraudCheckResult r = service.registrarIntento(TARJETA_A);

        assertThat(r.bloqueado()).isFalse();
        assertThat(r.intentosEnVentana()).isEqualTo(1);
    }

    @Test
    void ventanaDeslizante_soloSaleElIntentoMasViejo() {
        service.registrarIntento(TARJETA_A);           // t=0
        reloj.avanzar(Duration.ofSeconds(5));
        service.registrarIntento(TARJETA_A);           // t=5
        service.registrarIntento(TARJETA_A);           // t=5
        reloj.avanzar(Duration.ofSeconds(6));          // t=11: sale solo el de t=0

        FraudCheckResult r = service.registrarIntento(TARJETA_A);

        assertThat(r.intentosEnVentana()).isEqualTo(3);
        assertThat(r.bloqueado()).isFalse();
    }

    @Test
    void tarjetasDistintas_sonIndependientes() {
        for (int i = 0; i < 4; i++) {
            service.registrarIntento(TARJETA_A);
        }
        FraudCheckResult otraTarjeta = service.registrarIntento(TARJETA_B);

        assertThat(otraTarjeta.bloqueado()).isFalse();
        assertThat(otraTarjeta.intentosEnVentana()).isEqualTo(1);
    }

    @Test
    void limpieza_eliminaTarjetasSinIntentosRecientes() {
        service.registrarIntento(TARJETA_A);
        reloj.avanzar(Duration.ofSeconds(8));
        service.registrarIntento(TARJETA_B);
        reloj.avanzar(Duration.ofSeconds(3)); // A tiene 11 s (vieja), B tiene 3 s (reciente)

        service.limpiarEntradasViejas();

        assertThat(service.tarjetasEnMemoria()).isEqualTo(1);
    }

    @Test
    void parametrosConfigurables() {
        FraudProperties props = new FraudProperties();
        props.setMaxAttempts(1);
        props.setWindowSeconds(60);
        FraudDetectionService estricto = new FraudDetectionService(reloj, props);

        estricto.registrarIntento(TARJETA_A);
        reloj.avanzar(Duration.ofSeconds(30));

        assertThat(estricto.registrarIntento(TARJETA_A).bloqueado()).isTrue();
    }
}
