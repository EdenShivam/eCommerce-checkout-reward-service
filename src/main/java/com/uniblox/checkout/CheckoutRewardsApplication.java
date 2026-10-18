package com.uniblox.checkout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CheckoutRewardsApplication {

    public static void main(String[] args) {
        SpringApplication.run(CheckoutRewardsApplication.class, args);
    }
}
