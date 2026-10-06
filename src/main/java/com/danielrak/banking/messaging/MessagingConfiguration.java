package com.danielrak.banking.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * RabbitMQ topology (design.md §5), declared by Boot's {@code RabbitAdmin} on the first connection, and
 * scheduling for {@link OutboxPublisher}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class MessagingConfiguration {

    public static final String EXCHANGE = "banking.events";
    public static final String DEMO_QUEUE = "banking.events.all";

    /** Caps the demo queue, which has no consumer under compose. Queue arguments can't change once declared. */
    static final int DEMO_QUEUE_MAX_LENGTH = 10_000;

    @Bean
    TopicExchange bankingEventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    /** Every event, so they can be browsed in the RabbitMQ UI; the oldest are dropped past the cap. */
    @Bean
    Queue demoQueue() {
        return QueueBuilder.durable(DEMO_QUEUE)
                .maxLength(DEMO_QUEUE_MAX_LENGTH)
                .overflow(QueueBuilder.Overflow.dropHead)
                .build();
    }

    @Bean
    Binding demoQueueBinding(Queue demoQueue, TopicExchange bankingEventsExchange) {
        return BindingBuilder.bind(demoQueue).to(bankingEventsExchange).with("#");
    }
}
