package cl.duoc.pedidos360.ms_pago.service;

import cl.duoc.pedidos360.ms_pago.config.FraudProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Antifraude por VENTANA DESLIZANTE. La clave es una tarjeta ("card:<hash>") o un usuario ("user:<oid>"):
 * PaymentService registra cada intento en ambas, así cambiar de tarjeta no evita el conteo del usuario.
 *
 * Por cada clave guardamos los instantes de sus intentos recientes. En cada intento nuevo:
 *  1) se descartan los intentos más viejos que la ventana (ej. 10 s),
 *  2) se agrega el intento actual (los bloqueados también cuentan),
 *  3) si la cantidad supera el máximo (ej. 3), el intento se bloquea.
 *
 * Limitación: la ventana vive en memoria. Se pierde al reiniciar y no se comparte entre
 * instancias del servicio (para eso habría que usar Redis o la BD).
 */
@Slf4j
@Service
public class FraudDetectionService {

    private final Clock clock;
    private final FraudProperties props;

    public static final String PREFIJO_TARJETA = "card:";
    public static final String PREFIJO_USUARIO = "user:";

    /** clave (tarjeta o usuario) -> instantes de los intentos dentro de la ventana (del más viejo al más nuevo) */
    private final ConcurrentHashMap<String, Deque<Instant>> intentosPorTarjeta = new ConcurrentHashMap<>();

    public FraudDetectionService(Clock clock, FraudProperties props) {
        this.clock = clock;
        this.props = props;
    }

    public static String claveTarjeta(String cardHash) {
        return PREFIJO_TARJETA + cardHash;
    }

    public static String claveUsuario(String userId) {
        return PREFIJO_USUARIO + userId;
    }

    /** Registra un intento para la clave (tarjeta o usuario) y dice si supera el máximo */
    public FraudCheckResult registrarIntento(String cardHash) {
        Instant ahora = clock.instant();
        int[] cantidad = new int[1];

        // compute() es atómico por clave: dos intentos simultáneos de la misma tarjeta
        // no se pisan, y la limpieza periódica no puede borrar la lista a mitad de camino.
        intentosPorTarjeta.compute(cardHash, (clave, intentos) -> {
            Deque<Instant> lista = (intentos == null) ? new ArrayDeque<>() : intentos;
            descartarViejos(lista, ahora);
            lista.addLast(ahora);
            cantidad[0] = lista.size();
            return lista;
        });

        boolean bloqueado = cantidad[0] > props.getMaxAttempts();
        return new FraudCheckResult(bloqueado, cantidad[0]);
    }

    /**
     * Limpieza periódica: elimina las tarjetas sin intentos recientes para que el mapa
     * no crezca sin límite en memoria.
     */
    @Scheduled(fixedDelayString = "${fraud.cleanup-interval-ms:60000}")
    public void limpiarEntradasViejas() {
        Instant ahora = clock.instant();
        int antes = intentosPorTarjeta.size();
        for (String cardHash : intentosPorTarjeta.keySet()) {
            // devolver null en computeIfPresent elimina la entrada del mapa
            intentosPorTarjeta.computeIfPresent(cardHash, (clave, lista) -> {
                descartarViejos(lista, ahora);
                return lista.isEmpty() ? null : lista;
            });
        }
        int eliminadas = antes - intentosPorTarjeta.size();
        if (eliminadas > 0) {
            log.debug("Antifraude: {} tarjetas sin actividad reciente eliminadas de memoria", eliminadas);
        }
    }

    /** Borra los intentos de una clave (al desbloquear a un usuario se parte de cero) */
    public void olvidar(String clave) {
        intentosPorTarjeta.remove(clave);
    }

    /** Cantidad de claves en memoria (útil para tests y monitoreo) */
    public int tarjetasEnMemoria() {
        return intentosPorTarjeta.size();
    }

    /** Un intento queda fuera si ocurrió hace windowSeconds o más */
    private void descartarViejos(Deque<Instant> lista, Instant ahora) {
        Instant limite = ahora.minus(Duration.ofSeconds(props.getWindowSeconds()));
        while (!lista.isEmpty() && !lista.peekFirst().isAfter(limite)) {
            lista.pollFirst();
        }
    }
}
