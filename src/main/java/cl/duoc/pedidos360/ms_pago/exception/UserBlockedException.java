package cl.duoc.pedidos360.ms_pago.exception;

import lombok.Getter;

import java.time.Instant;

/** El usuario está bloqueado por el antifraude -> 423 Locked con {"error":"USER_BLOCKED","until":...} */
@Getter
public class UserBlockedException extends RuntimeException {

    private final transient Instant until;

    public UserBlockedException(String userId, Instant until) {
        super("Usuario " + userId + " bloqueado por actividad sospechosa hasta " + until);
        this.until = until;
    }
}
