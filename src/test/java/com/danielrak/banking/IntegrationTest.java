package com.danielrak.banking;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;

/**
 * The one annotation for integration tests: the full app on a random port, a {@code RestTestClient}
 * bound to it, and the shared Testcontainers. Spring's context cache reuses a context, and so its
 * containers, only when the test configuration is identical, so every integration test uses exactly
 * this and adds nothing that changes the context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
public @interface IntegrationTest {
}
