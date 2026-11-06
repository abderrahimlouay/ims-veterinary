package com.imsvet.ims.common.web;

import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.descriptor.web.SecurityCollection;
import org.apache.tomcat.util.descriptor.web.SecurityConstraint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ADR-166: the application itself serves HTTPS ({@code server.ssl}, the shop's private-CA certificate, ADR-152) and
 * answers plain HTTP on {@code ims.server.http-port} only to redirect to it, so a device that types the bare address
 * still ends on HTTPS. Every path is marked confidential, which makes Tomcat send the redirect.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "server.ssl.enabled")
public class HttpsRedirectConfiguration {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> httpRedirect(
            @Value("${ims.server.http-port:-1}") int httpPort, @Value("${server.port}") int httpsPort) {
        return factory -> {
            if (httpPort <= 0 || httpsPort <= 0) {
                return;
            }
            Connector http = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            http.setScheme("http");
            http.setPort(httpPort);
            http.setSecure(false);
            http.setRedirectPort(httpsPort);
            factory.addAdditionalConnectors(http);
            factory.addContextCustomizers(context -> {
                SecurityConstraint confidential = new SecurityConstraint();
                confidential.setUserConstraint("CONFIDENTIAL");
                SecurityCollection everything = new SecurityCollection();
                everything.addPattern("/*");
                confidential.addCollection(everything);
                context.addConstraint(confidential);
            });
        };
    }
}
