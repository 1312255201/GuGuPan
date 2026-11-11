package cn.gugupan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(PanProperties.class)
public class GuGuPanApplication {
    public static void main(String[] args) {
        SpringApplication.run(GuGuPanApplication.class, args);
    }
}
