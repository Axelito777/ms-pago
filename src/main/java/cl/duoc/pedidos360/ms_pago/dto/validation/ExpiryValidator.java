package cl.duoc.pedidos360.ms_pago.dto.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.Clock;
import java.time.YearMonth;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Implementación de @ValidExpiry */
public class ExpiryValidator implements ConstraintValidator<ValidExpiry, String> {

    private static final Pattern FORMATO = Pattern.compile("(0[1-9]|1[0-2])/(\\d{2})");

    private final Clock clock;

    /** Constructor usado por el validador en tiempo de ejecución */
    public ExpiryValidator() {
        this(Clock.systemDefaultZone());
    }

    /** Constructor para tests (fecha controlada) */
    public ExpiryValidator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean isValid(String valor, ConstraintValidatorContext context) {
        if (valor == null || valor.isBlank()) {
            return true; // de "obligatorio" se encarga @NotBlank
        }
        Matcher m = FORMATO.matcher(valor.trim());
        if (!m.matches()) {
            return false;
        }
        YearMonth vencimiento = YearMonth.of(2000 + Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        // Vale durante todo el mes de vencimiento
        return !vencimiento.isBefore(YearMonth.now(clock));
    }
}
