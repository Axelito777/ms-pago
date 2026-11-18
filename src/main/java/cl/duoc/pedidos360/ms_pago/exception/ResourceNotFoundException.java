package cl.duoc.pedidos360.ms_pago.exception;

/** Pago inexistente -> 404 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
