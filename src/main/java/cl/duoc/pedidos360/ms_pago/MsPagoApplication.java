package cl.duoc.pedidos360.ms_pago;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan // registra FraudProperties
@EnableScheduling            // limpieza periódica de la ventana antifraude
public class MsPagoApplication {

	public static void main(String[] args) {
		SpringApplication.run(MsPagoApplication.class, args);
	}
}
