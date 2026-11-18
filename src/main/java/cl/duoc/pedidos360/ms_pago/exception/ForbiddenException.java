package cl.duoc.pedidos360.ms_pago.exception;

/** Token válido pero sin permiso (no es Admin ni dueño) -> 403 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
