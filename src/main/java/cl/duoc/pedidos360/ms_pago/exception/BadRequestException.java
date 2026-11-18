package cl.duoc.pedidos360.ms_pago.exception;

/** Petición incompleta (ej. no se puede determinar el userId) -> 400 */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
