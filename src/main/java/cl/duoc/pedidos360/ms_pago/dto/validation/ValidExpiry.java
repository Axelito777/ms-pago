package cl.duoc.pedidos360.ms_pago.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Valida que la fecha de vencimiento tenga formato MM/YY y no esté vencida.
 * Una tarjeta con vencimiento 05/27 es válida hasta el último día de mayo de 2027.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ExpiryValidator.class)
public @interface ValidExpiry {

    String message() default "expiry debe tener formato MM/YY y no estar vencida";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
