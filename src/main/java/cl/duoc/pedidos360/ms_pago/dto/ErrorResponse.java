package cl.duoc.pedidos360.ms_pago.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Formato único de error que devuelve el servicio */
public record ErrorResponse(LocalDateTime timestamp, int status, String error, String message,
                            String path, List<String> details) {
}
