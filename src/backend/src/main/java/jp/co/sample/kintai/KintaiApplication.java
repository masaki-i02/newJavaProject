package jp.co.sample.kintai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class KintaiApplication {

    public static void main(String[] args) {
        SpringApplication.run(KintaiApplication.class, args);
    }
}
