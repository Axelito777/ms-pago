package cl.duoc.pedidos360.ms_pago;

import cl.duoc.pedidos360.ms_pago.repository.PaymentRepository;
import cl.duoc.pedidos360.ms_pago.support.TokenDePrueba;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
}
