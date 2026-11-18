package cl.duoc.pedidos360.ms_pago.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** Reloj para tests: el tiempo solo avanza cuando el test lo pide (sin Thread.sleep) */
public class RelojControlable extends Clock {

    private Instant ahora;
    private final ZoneId zona;

    public RelojControlable(Instant inicio) {
        this(inicio, ZoneId.of("America/Santiago"));
    }

    private RelojControlable(Instant inicio, ZoneId zona) {
        this.ahora = inicio;
        this.zona = zona;
    }

    public void avanzar(Duration duracion) {
        ahora = ahora.plus(duracion);
    }

    @Override
    public ZoneId getZone() {
        return zona;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new RelojControlable(ahora, zone);
    }

    @Override
    public Instant instant() {
        return ahora;
    }
}
