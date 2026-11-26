package cl.duoc.pedidos360.ms_pago.service;

import cl.duoc.pedidos360.ms_pago.config.FraudProperties;
import cl.duoc.pedidos360.ms_pago.exception.UserBlockedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bloqueo temporal de USUARIOS (por oid) cuando el antifraude detecta el patrón.
 * Mientras dura: pagos, Webpay, confirmar carrito y crear pedidos responden 423.
 *
 * El bloqueo expira solo: si la hora "hasta" ya pasó, el usuario cuenta como no bloqueado.
 * Limitación: vive en memoria (igual que la ventana). Se pierde al reiniciar ms-pago.
 */
@Slf4j
@Service
public class UserBlockService {

    private final Clock clock;
    private final FraudProperties props;
    private final FraudDetectionService fraudDetectionService;

    /** oid -> instante hasta el que está bloqueado */
    private final ConcurrentHashMap<String, Instant> bloqueos = new ConcurrentHashMap<>();

    public UserBlockService(Clock clock, FraudProperties props, FraudDetectionService fraudDetectionService) {
        this.clock = clock;
        this.props = props;
        this.fraudDetectionService = fraudDetectionService;
    }

    /** Bloquea al usuario por blockMinutes desde ahora y devuelve hasta cuándo */
    public Instant bloquear(String userId) {
        Instant hasta = clock.instant().plus(Duration.ofMinutes(props.getBlockMinutes()));
        // merge: si ya tenía un bloqueo más largo, se conserva el mayor
        Instant efectivo = bloqueos.merge(userId, hasta, (actual, nuevo) -> actual.isAfter(nuevo) ? actual : nuevo);
        log.warn("USUARIO BLOQUEADO por posible fraude: usuario={} hasta={}", userId, efectivo);
        return efectivo;
    }

    /** Hasta cuándo está bloqueado, o vacío si no lo está (o si su bloqueo ya expiró) */
    public Optional<Instant> bloqueadoHasta(String userId) {
        if (userId == null) {
            return Optional.empty();
        }
        Instant hasta = bloqueos.get(userId);
        if (hasta == null) {
            return Optional.empty();
        }
        if (!hasta.isAfter(clock.instant())) {
            bloqueos.remove(userId, hasta); // expiró: se limpia
            return Optional.empty();
        }
        return Optional.of(hasta);
    }

    /** Lanza UserBlockedException (423) si el usuario está bloqueado */
    public void verificarNoBloqueado(String userId) {
        bloqueadoHasta(userId).ifPresent(hasta -> {
            throw new UserBlockedException(userId, hasta);
        });
    }

    /** Desbloqueo manual (Admin, para la demo). También borra su conteo en la ventana. */
    public boolean desbloquear(String userId) {
        boolean estaba = bloqueos.remove(userId) != null;
        fraudDetectionService.olvidar(FraudDetectionService.claveUsuario(userId));
        log.info("Usuario {} desbloqueado manualmente (estaba bloqueado: {})", userId, estaba);
        return estaba;
    }

    /** Limpieza periódica de bloqueos vencidos, para que el mapa no crezca sin límite */
    @Scheduled(fixedDelayString = "${fraud.cleanup-interval-ms:60000}")
    public void limpiarVencidos() {
        Instant ahora = clock.instant();
        bloqueos.entrySet().removeIf(e -> !e.getValue().isAfter(ahora));
    }

    /** Usuarios bloqueados en memoria (útil para tests) */
    public int bloqueosEnMemoria() {
        return bloqueos.size();
    }
}
