package cl.duoc.pedidos360.ms_pago.service;

import cl.duoc.pedidos360.ms_pago.config.FraudProperties;
import cl.duoc.pedidos360.ms_pago.exception.UserBlockedException;
import cl.duoc.pedidos360.ms_pago.support.RelojControlable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bloqueo temporal por usuario: duración, expiración, desbloqueo y limpieza */
class UserBlockServiceTest {

    private RelojControlable reloj;
    private FraudProperties props;
    private FraudDetectionService fraude;
    private UserBlockService service;

    @BeforeEach
    void setUp() {
        reloj = new RelojControlable(Instant.parse("2026-11-26T12:00:00Z"));
        props = new FraudProperties(); // bloqueo de 10 minutos
        fraude = new FraudDetectionService(reloj, props);
        service = new UserBlockService(reloj, props, fraude);
    }

    @Test
    void bloquear_duraBlockMinutes() {
        Instant hasta = service.bloquear("u1");

        assertThat(hasta).isEqualTo(Instant.parse("2026-11-26T12:10:00Z"));
        assertThat(service.bloqueadoHasta("u1")).contains(hasta);
        assertThatThrownBy(() -> service.verificarNoBloqueado("u1")).isInstanceOf(UserBlockedException.class);
    }

    @Test
    void expiraSolo() {
        service.bloquear("u1");
        reloj.avanzar(Duration.ofMinutes(10));

        assertThat(service.bloqueadoHasta("u1")).isEmpty();
        assertThatCode(() -> service.verificarNoBloqueado("u1")).doesNotThrowAnyException();
    }

    @Test
    void desbloquear_quitaElBloqueoYElConteoDelUsuario() {
        fraude.registrarIntento(FraudDetectionService.claveUsuario("u1"));
        service.bloquear("u1");

        assertThat(service.desbloquear("u1")).isTrue();

        assertThat(service.bloqueadoHasta("u1")).isEmpty();
        // El conteo del usuario partió de cero: el siguiente intento es el 1.º
        assertThat(fraude.registrarIntento(FraudDetectionService.claveUsuario("u1")).intentosEnVentana()).isEqualTo(1);
    }

    @Test
    void desbloquear_usuarioNoBloqueado_devuelveFalse() {
        assertThat(service.desbloquear("nadie")).isFalse();
    }

    @Test
    void otroUsuario_noSeVeAfectado() {
        service.bloquear("u1");

        assertThat(service.bloqueadoHasta("u2")).isEmpty();
    }

    @Test
    void bloquearDeNuevo_conservaElMasLargo() {
        Instant primero = service.bloquear("u1");
        props.setBlockMinutes(1);

        assertThat(service.bloquear("u1")).isEqualTo(primero);
    }

    @Test
    void limpieza_eliminaBloqueosVencidos() {
        service.bloquear("u1");
        reloj.avanzar(Duration.ofMinutes(11));

        service.limpiarVencidos();

        assertThat(service.bloqueosEnMemoria()).isZero();
    }

    @Test
    void userIdNull_noEstaBloqueado() {
        assertThat(service.bloqueadoHasta(null)).isEmpty();
    }
}
