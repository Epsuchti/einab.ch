package ch.it4user.foodsharing.config;

import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/i18n/**")
                .addResourceLocations("classpath:/static/i18n/");
    }

    /** The web app manifest (installable website / PWA) needs its own content type, which Tomcat does not know. */
    @Bean
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> webManifestMimeType() {
        return factory -> factory.addMimeMappings(mimeMappings());
    }

    private static org.springframework.boot.web.server.MimeMappings mimeMappings() {
        org.springframework.boot.web.server.MimeMappings mappings = new org.springframework.boot.web.server.MimeMappings();
        mappings.add("webmanifest", "application/manifest+json");
        return mappings;
    }
}
