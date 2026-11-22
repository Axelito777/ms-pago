package cl.duoc.pedidos360.ms_pago;

import cl.duoc.pedidos360.ms_pago.repository.PaymentRepository;
import cl.duoc.pedidos360.ms_pago.service.webpay.WebpayClient;
import cl.duoc.pedidos360.ms_pago.support.FakeWebpayClient;
import cl.duoc.pedidos360.ms_pago.support.TokenDePrueba;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contexto completo con H2 y SIN broker de Kafka (perfil test: listener apagado).
 * Demuestra que POST /payments responde aunque Kafka no esté disponible.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MsPagoApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PaymentRepository paymentRepository;

	/** Decoder con la llave RSA de prueba + validadores de producción (sin red) */
	@TestBean
	private JwtDecoder jwtDecoder;

	static JwtDecoder jwtDecoder() {
		return TokenDePrueba.decoder();
	}

	/** Transbank falso: el flujo Webpay se prueba completo sin red */
	private static final FakeWebpayClient TRANSBANK = new FakeWebpayClient();

	@TestBean
	private WebpayClient webpayClient;

	static WebpayClient webpayClient() {
		return TRANSBANK;
	}

	@Test
	void contextLoads() {
	}

	@Test
	void pagoSeGuardaYRespondeAunqueKafkaEsteCaido() throws Exception {
		String body = """
				{"orderId": 77, "userId": "user-it", "cardNumber": "5555555555554444", "cardHolder": "Ana Soto",
				 "expiry": "12/30", "cvv": "321", "amount": 19990}
				""";

		// Token firmado con payments.write (SecurityConfig exige token); oid = userId del body
		String token = TokenDePrueba.bearer(Map.of("oid", "user-it", "scp", "payments.write"));

		mockMvc.perform(post("/payments").header("Authorization", token)
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated());

		assertThat(paymentRepository.findByUserIdOrderByCreatedAtDesc("user-it"))
				.singleElement()
				.satisfies(p -> {
					assertThat(p.getLast4()).isEqualTo("4444");
					assertThat(p.getCardHash()).hasSize(64).doesNotContain("5555555555554444");
				});
	}

	@Test
	void flujoWebpay_initReturnYConsulta_aunqueKafkaEsteCaido() throws Exception {
		String token = TokenDePrueba.bearer(Map.of("oid", "user-wp", "scp", "payments.write payments.read"));

		// 1) init con JWT
		String init = mockMvc.perform(post("/payments/webpay/init").header("Authorization", token)
						.contentType(MediaType.APPLICATION_JSON).content("{\"orderId\": 88, \"amount\": 15990}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		Integer paymentId = JsonPath.read(init, "$.paymentId");
		String tokenWs = JsonPath.read(init, "$.token");

		// 2) el navegador vuelve de Transbank SIN JWT
		mockMvc.perform(post("/payments/webpay/return")
						.contentType(MediaType.APPLICATION_FORM_URLENCODED).content("token_ws=" + tokenWs))
				.andExpect(status().isFound())
				.andExpect(header().string("Location",
						"http://front.test/#/pago/resultado?paymentId=" + paymentId + "&status=APPROVED"));

		// 3) el frontend verifica el pago con JWT: trae method y orderId (= cartId)
		mockMvc.perform(get("/payments/" + paymentId).header("Authorization", token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("APPROVED"))
				.andExpect(jsonPath("$.method").value("WEBPAY"))
				.andExpect(jsonPath("$.orderId").value(88))
				.andExpect(jsonPath("$.last4").value("6623"))
				.andExpect(jsonPath("$.authorizationCode").value("1213"));
	}
}
