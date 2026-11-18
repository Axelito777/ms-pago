package cl.duoc.pedidos360.ms_pago.dto;

import cl.duoc.pedidos360.ms_pago.dto.validation.ValidExpiry;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * Body de POST /payments.
 * cardNumber y cvv están excluidos del toString() para que nunca terminen en un log por accidente.
 * Los mensajes de validación no repiten el valor recibido.
 * Solo constructor vacío + setters a propósito: si hubiera un constructor con todos los campos,
 * Jackson 3 lo usaría y currency quedaría null en vez de "CLP" cuando el JSON no lo trae.
 */
@Data
@NoArgsConstructor
public class PaymentRequest {

    @NotNull(message = "orderId es obligatorio")
    @Positive(message = "orderId debe ser positivo")
    private Long orderId;

    /** Opcional: solo se usa si no viene token (o si quien llama es Admin) */
    private String userId;

    @ToString.Exclude
    @NotBlank(message = "cardNumber es obligatorio")
    @Pattern(regexp = "\\d{13,19}", message = "cardNumber debe tener entre 13 y 19 dígitos, sin espacios")
    private String cardNumber;

    @NotBlank(message = "cardHolder es obligatorio")
    @Size(max = 100, message = "cardHolder no puede superar 100 caracteres")
    private String cardHolder;

    @NotBlank(message = "expiry es obligatorio")
    @ValidExpiry
    private String expiry;

    @ToString.Exclude
    @NotBlank(message = "cvv es obligatorio")
    @Pattern(regexp = "\\d{3,4}", message = "cvv debe tener 3 o 4 dígitos")
    private String cvv;

    @NotNull(message = "amount es obligatorio")
    @DecimalMin(value = "0", inclusive = false, message = "amount debe ser mayor que 0")
    @Digits(integer = 13, fraction = 2, message = "amount admite hasta 13 enteros y 2 decimales")
    private BigDecimal amount;

    // Si el JSON no trae currency, queda "CLP"
    @NotBlank(message = "currency es obligatorio")
    @Pattern(regexp = "[A-Z]{3}", message = "currency debe ser un código ISO de 3 letras mayúsculas, ej. CLP")
    private String currency = "CLP";
}
