package com.bnaqica.documentreader.config;

import com.bnaqica.documentreader.processors.HeicConverter;
import com.bnaqica.documentreader.processors.ImageConversionProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The page converter, with its size settings and the HEIC decoder found on the {@code PATH}. */
@Configuration
public class ConversionConfig {

    @Bean
    HeicConverter heicConverter() {
        return HeicConverter.onPath();
    }

    @Bean
    ImageConversionProcessor imageConversionProcessor(DocumentReaderProperties properties, HeicConverter heic) {
        return new ImageConversionProcessor(
                properties.conversion().dpi(), properties.conversion().maxDimension(), heic);
    }
}
