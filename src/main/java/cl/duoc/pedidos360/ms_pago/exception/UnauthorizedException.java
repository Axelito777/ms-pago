package cl.duoc.pedidos360.ms_pago.exception;

/** Token ilegible o sin oid, o falta token donde es obligatorio -> 401 */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
