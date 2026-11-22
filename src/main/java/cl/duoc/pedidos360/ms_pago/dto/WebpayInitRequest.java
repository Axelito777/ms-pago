package cl.duoc.pedidos360.ms_pago.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Body de POST /payments/webpay/init. Mismos tipos y validaciones que PaymentRequest,
 * pero sin datos de tarjeta (la tarjeta se escribe en el sitio de Transbank).
 * orderId = cartId que manda el frontend, igual que en el pago simulado.
 * Solo constructor vacío + setters para que currency quede "CLP" si el JSON no lo trae (ver PaymentRequest).
 */
@Data
@NoArgsConstructor
public class WebpayInitRequest {

    @NotNull(message = "orderId es obligatorio")
    @Positive(message = "orderId debe ser positivo")
    private Long orderId;

    @NotNull(message = "amount es obligatorio")
    @DecimalMin(value = "0", inclusive = false, message = "amount debe ser mayor que 0")
    @Digits(integer = 13, fraction = 2, message = "amount admite hasta 13 enteros y 2 decimales")
    private BigDecimal amount;

    @NotBlank(message = "currency es obligatorio")
    @Pattern(regexp = "[A-Z]{3}", message = "currency debe ser un código ISO de 3 letras mayúsculas, ej. CLP")
    private String currency = "CLP";
}
