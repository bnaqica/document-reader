package com.bnaqica.documentreader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DocumentReaderApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocumentReaderApplication.class, args);
    }
}
