package com.forvia.partorderservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The producer owns the topic: Spring Boot's auto-configured KafkaAdmin
 * creates every NewTopic bean on startup (and skips it if it already exists).
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic partOrdersTopic(@Value("${app.kafka.topic}") String topic) {
        return TopicBuilder.name(topic)
                .partitions(3)   // orders are keyed by SKU -> same SKU always lands on the same partition
                .replicas(1)     // single-broker cluster, can't replicate further
                .build();
    }
}
