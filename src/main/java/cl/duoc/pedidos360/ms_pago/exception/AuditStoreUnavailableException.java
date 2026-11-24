package cl.duoc.pedidos360.ms_pago.exception;

/** La auditoría en DynamoDB está desactivada o no respondió -> 503 */
public class AuditStoreUnavailableException extends RuntimeException {
    public AuditStoreUnavailableException(String message) {
        super(message);
    }
}
