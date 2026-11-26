package cl.duoc.pedidos360.ms_pago.service;

import cl.duoc.pedidos360.ms_pago.config.WebpayProperties;
import cl.duoc.pedidos360.ms_pago.dto.WebpayInitRequest;
import cl.duoc.pedidos360.ms_pago.dto.WebpayInitResponse;
import cl.duoc.pedidos360.ms_pago.dto.WebpayReturnResult;
import cl.duoc.pedidos360.ms_pago.event.PaymentAttemptEvent;
import cl.duoc.pedidos360.ms_pago.event.PaymentEventPublisher;
import cl.duoc.pedidos360.ms_pago.exception.BadRequestException;
import cl.duoc.pedidos360.ms_pago.exception.WebpayGatewayException;
import cl.duoc.pedidos360.ms_pago.exception.WebpayNotConfiguredException;
import cl.duoc.pedidos360.ms_pago.model.Payment;
import cl.duoc.pedidos360.ms_pago.model.PaymentMethod;
import cl.duoc.pedidos360.ms_pago.model.PaymentStatus;
import cl.duoc.pedidos360.ms_pago.repository.PaymentRepository;
import cl.duoc.pedidos360.ms_pago.service.webpay.WebpayClient;
import cl.duoc.pedidos360.ms_pago.service.webpay.WebpayCommitResponse;
import cl.duoc.pedidos360.ms_pago.service.webpay.WebpayCreateResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Pago con Webpay Plus (segundo método de pago; el simulado y su antifraude no cambian).
 *
 * Flujo:
 *  1) iniciar: Payment PENDING -> Transbank crea la transacción -> devolvemos token + url al frontend.
 *  2) el usuario paga en el sitio de Transbank (la tarjeta nunca pasa por nosotros, por eso no hay antifraude por tarjeta).
 *  3) procesarRetorno: Transbank devuelve al usuario; confirmamos (commit) o marcamos CANCELLED
 *     y redirigimos al frontend con el resultado.
 *
 * En los logs solo va el paymentId: nunca el token_ws ni la llave.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebpayService {

    static final String PREFIJO_BUY_ORDER = "P360-";
    static final int MAX_BUY_ORDER = 26;
    static final String CLP = "CLP";
    static final String ESTADO_DESCONOCIDO = "UNKNOWN";

    private final WebpayClient webpayClient;
    private final UserBlockService userBlockService;
    private final WebpayProperties props;
    private final PaymentRepository paymentRepository;
    private final PaymentEventPublisher eventPublisher;
    private final Clock clock;

    // ------------------------------ 1) INICIAR ------------------------------

    public WebpayInitResponse iniciar(WebpayInitRequest request, String userId) {
        // Usuario bloqueado por el antifraude: 423, no se crea nada en Transbank
        userBlockService.verificarNoBloqueado(userId);
        if (!props.puedeIniciar()) {
            throw new WebpayNotConfiguredException("Webpay no está configurado: faltan WEBPAY_COMMERCE_CODE, "
                    + "WEBPAY_API_KEY o WEBPAY_RETURN_URL");
        }
        long monto = montoEnPesos(request);

        // Se guarda primero para tener el id, que forma parte del buyOrder
        Payment pago = paymentRepository.save(Payment.builder()
                .method(PaymentMethod.WEBPAY)
                .orderId(request.getOrderId())
                .userId(userId)
                .amount(request.getAmount())
                .currency(CLP)
                .status(PaymentStatus.PENDING)
                .message("Esperando el pago en Webpay")
                .createdAt(LocalDateTime.now(clock))
                .build());
        pago.setBuyOrder(generarBuyOrder(pago.getId(), clock.instant()));
        pago = paymentRepository.save(pago);

        try {
            // session_id = userId (oid del token)
            WebpayCreateResponse tx = webpayClient.create(pago.getBuyOrder(), userId, monto, props.getReturnUrl());
            pago.setWebpayToken(tx.token());
            pago = paymentRepository.save(pago);
            log.info("Webpay: pago {} creado en Transbank (PENDING) orden={} usuario={}",
                    pago.getId(), pago.getOrderId(), userId);
            return new WebpayInitResponse(pago.getId(), tx.token(), tx.url(), pago.getBuyOrder());
        } catch (WebpayGatewayException e) {
            // Transbank falló o no respondió: el pago queda REJECTED y el controller responde 502
            finalizar(pago, PaymentStatus.REJECTED, "No se pudo crear la transacción en Webpay: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Orden de compra para Transbank: "P360-" + paymentId + "-" + milisegundos en base 36, recortado a 26.
     * Es única porque el paymentId es único; aunque el id fuera enorme y el recorte comiera los milisegundos,
     * el id completo cabe siempre (Long tiene como máximo 19 dígitos: 5 + 19 = 24 caracteres).
     */
    static String generarBuyOrder(Long paymentId, Instant ahora) {
        String buyOrder = PREFIJO_BUY_ORDER + paymentId + "-" + Long.toString(ahora.toEpochMilli(), 36);
        return buyOrder.length() > MAX_BUY_ORDER ? buyOrder.substring(0, MAX_BUY_ORDER) : buyOrder;
    }

    /** Webpay Plus cobra solo pesos chilenos enteros */
    private long montoEnPesos(WebpayInitRequest request) {
        if (!CLP.equals(request.getCurrency())) {
            throw new BadRequestException("Webpay solo acepta currency CLP");
        }
        try {
            return request.getAmount().stripTrailingZeros().longValueExact();
        } catch (ArithmeticException e) {
            throw new BadRequestException("Webpay solo acepta montos enteros en CLP (sin decimales)");
        }
    }

    // ------------------------------ 3) RETORNO ------------------------------

    /**
     * Decide qué pasó según los parámetros con que Transbank devolvió al usuario:
     *  - TBK_TOKEN (con o sin token_ws): el usuario anuló, o hubo un error en el formulario -> CANCELLED, sin commit.
     *  - solo token_ws: pago normal -> commit.
     *  - solo TBK_ORDEN_COMPRA / TBK_ID_SESION: se agotó el tiempo -> CANCELLED.
     */
    public WebpayReturnResult procesarRetorno(String tokenWs, String tbkToken, String tbkOrdenCompra, String tbkIdSesion) {
        // Sin URL de resultado no hay adónde redirigir: se avisa ANTES de tocar el pago.
        // Si no se confirma, Transbank reversa la transacción sola.
        if (!props.tieneUrlResultado()) {
            throw new WebpayNotConfiguredException("Webpay no está configurado: falta WEBPAY_FRONTEND_RESULT_URL");
        }

        Optional<Payment> pago;
        if (tieneValor(tbkToken) || (!tieneValor(tokenWs) && tieneValor(tbkOrdenCompra))) {
            pago = cancelar(tbkToken, tbkOrdenCompra, tbkIdSesion);
        } else if (tieneValor(tokenWs)) {
            pago = confirmar(tokenWs);
        } else {
            log.warn("Webpay: retorno sin token_ws ni TBK_ORDEN_COMPRA");
            pago = Optional.empty();
        }

        Long paymentId = pago.map(Payment::getId).orElse(null);
        String status = pago.map(p -> p.getStatus().name()).orElse(ESTADO_DESCONOCIDO);
        return new WebpayReturnResult(paymentId, status, urlResultado(paymentId, status));
    }

    /** Retorno normal con token_ws: commit solo si el pago sigue PENDING */
    private Optional<Payment> confirmar(String tokenWs) {
        Optional<Payment> encontrado = paymentRepository.findByWebpayToken(tokenWs);
        if (encontrado.isEmpty()) {
            log.warn("Webpay: retorno con un token_ws que no corresponde a ningún pago");
            return Optional.empty();
        }
        Payment pago = encontrado.get();
        if (pago.getStatus() != PaymentStatus.PENDING) {
            // Ya en estado final (ej. el usuario recargó la página): Transbank rechaza un segundo commit
            log.info("Webpay: pago {} ya estaba {}, no se vuelve a confirmar", pago.getId(), pago.getStatus());
            return Optional.of(pago);
        }

        WebpayCommitResponse commit;
        try {
            commit = webpayClient.commit(tokenWs);
        } catch (WebpayGatewayException e) {
            // Sin commit, Transbank reversa la transacción: no hay cobro, por eso REJECTED
            return Optional.of(finalizar(pago, PaymentStatus.REJECTED, "No se pudo confirmar con Webpay: " + e.getMessage()));
        }

        pago.setLast4(commit.last4());
        pago.setAuthorizationCode(commit.authorizationCode());

        if (!commit.autorizada()) {
            return Optional.of(finalizar(pago, PaymentStatus.REJECTED,
                    "Pago rechazado por Webpay (response_code " + commit.responseCode() + ")"));
        }
        if (!mismoMonto(pago.getAmount(), commit.amount())) {
            // No debería pasar nunca; si pasa, no se entrega la compra
            log.warn("Webpay: pago {} autorizado con un monto distinto al esperado", pago.getId());
            return Optional.of(finalizar(pago, PaymentStatus.REJECTED,
                    "El monto autorizado por Webpay no coincide con el del pago"));
        }
        return Optional.of(finalizar(pago, PaymentStatus.APPROVED, "Pago aprobado por Webpay"));
    }

    /** Anulación (TBK_TOKEN) o tiempo agotado: se busca por buyOrder y queda CANCELLED si seguía PENDING */
    private Optional<Payment> cancelar(String tbkToken, String tbkOrdenCompra, String tbkIdSesion) {
        Optional<Payment> encontrado = tieneValor(tbkOrdenCompra)
                ? paymentRepository.findByBuyOrder(tbkOrdenCompra)
                : paymentRepository.findByWebpayToken(tbkToken); // por si no viene la orden de compra
        if (encontrado.isEmpty()) {
            log.warn("Webpay: anulación/tiempo agotado de un pago que no existe");
            return Optional.empty();
        }
        Payment pago = encontrado.get();
        // La sesión es el userId que enviamos al crear: si no calza, no se toca el pago
        if (tieneValor(tbkIdSesion) && !tbkIdSesion.equals(pago.getUserId())) {
            log.warn("Webpay: TBK_ID_SESION no corresponde al dueño del pago {}, se ignora", pago.getId());
            return Optional.empty();
        }
        if (pago.getStatus() != PaymentStatus.PENDING) {
            log.info("Webpay: pago {} ya estaba {}, no se marca CANCELLED", pago.getId(), pago.getStatus());
            return Optional.of(pago);
        }
        String motivo = tieneValor(tbkToken) ? "El usuario anuló el pago en Webpay" : "Se agotó el tiempo para pagar en Webpay";
        return Optional.of(finalizar(pago, PaymentStatus.CANCELLED, motivo));
    }

    /** Guarda el estado final y publica el intento en payment-attempts (si Kafka falla, sigue) */
    private Payment finalizar(Payment pago, PaymentStatus status, String mensaje) {
        pago.setStatus(status);
        pago.setMessage(mensaje);
        Payment guardado = paymentRepository.save(pago);
        log.info("Webpay: pago {} orden={} usuario={} estado={}",
                guardado.getId(), guardado.getOrderId(), guardado.getUserId(), status);

        eventPublisher.publicar(new PaymentAttemptEvent(
                UUID.randomUUID().toString(),
                guardado.getId(),
                guardado.getOrderId(),
                guardado.getUserId(),
                null, // Webpay: no conocemos la tarjeta
                guardado.getLast4(),
                guardado.getAmount(),
                guardado.getCurrency(),
                guardado.getStatus(),
                false,
                clock.instant(),
                PaymentMethod.WEBPAY));
        return guardado;
    }

    /**
     * URL del frontend con el resultado. Se concatena como texto porque la URL lleva "#/pago/resultado"
     * (router por hash) y los parámetros deben ir DESPUÉS del #.
     */
    private String urlResultado(Long paymentId, String status) {
        return props.getFrontendResultUrl() + "?paymentId=" + (paymentId == null ? "" : paymentId) + "&status=" + status;
    }

    private static boolean mismoMonto(BigDecimal esperado, BigDecimal autorizado) {
        return autorizado != null && esperado.compareTo(autorizado) == 0;
    }

    private static boolean tieneValor(String valor) {
        return valor != null && !valor.isBlank();
    }
}
