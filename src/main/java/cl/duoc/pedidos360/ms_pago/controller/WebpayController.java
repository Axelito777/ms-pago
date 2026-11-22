package cl.duoc.pedidos360.ms_pago.controller;

import cl.duoc.pedidos360.ms_pago.dto.WebpayInitRequest;
import cl.duoc.pedidos360.ms_pago.dto.WebpayInitResponse;
import cl.duoc.pedidos360.ms_pago.dto.WebpayReturnResult;
import cl.duoc.pedidos360.ms_pago.security.JwtClaims;
import cl.duoc.pedidos360.ms_pago.service.WebpayService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de Webpay Plus.
 *  - POST /payments/webpay/init: con JWT y scope payments.write.
 *  - GET|POST /payments/webpay/return: PÚBLICO, lo llama el navegador al volver de Transbank.
 */
@RestController
@RequestMapping("/payments/webpay")
@RequiredArgsConstructor
public class WebpayController {

    private final WebpayService webpayService;
    private final JwtClaims jwtClaims;

    @PostMapping("/init")
    public ResponseEntity<WebpayInitResponse> iniciar(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody WebpayInitRequest request) {
        // El userId sale siempre del token (oid); aquí el JWT es obligatorio
        String userId = jwtClaims.resolverUserId(authHeader, null);
        return ResponseEntity.status(HttpStatus.CREATED).body(webpayService.iniciar(request, userId));
    }

    /**
     * Transbank devuelve al usuario por GET o POST (según el caso), con estos parámetros:
     *  - token_ws: pago terminado (aprobado o rechazado)
     *  - TBK_TOKEN + TBK_ORDEN_COMPRA + TBK_ID_SESION: el usuario anuló
     *  - TBK_ORDEN_COMPRA + TBK_ID_SESION: se agotó el tiempo
     * Se acepta TBK_ID_SESION y TBK_ID_SESSION (una o dos S). Siempre responde 302 al frontend.
     */
    @RequestMapping(value = "/return", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Void> retorno(
            @RequestParam(name = "token_ws", required = false) String tokenWs,
            @RequestParam(name = "TBK_TOKEN", required = false) String tbkToken,
            @RequestParam(name = "TBK_ORDEN_COMPRA", required = false) String tbkOrdenCompra,
            @RequestParam(name = "TBK_ID_SESION", required = false) String tbkIdSesion,
            @RequestParam(name = "TBK_ID_SESSION", required = false) String tbkIdSession) {
        String sesion = tbkIdSesion != null ? tbkIdSesion : tbkIdSession;
        WebpayReturnResult resultado = webpayService.procesarRetorno(tokenWs, tbkToken, tbkOrdenCompra, sesion);
        // Location como texto: la URL del frontend lleva "#/..." y no queremos que se reinterprete
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, resultado.redirectUrl())
                .build();
    }
}
