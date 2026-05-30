package dev.allstak.spring;

import dev.allstak.internal.SdkLogger;
import dev.allstak.kafka.AllStakKafkaConsumerInterceptor;
import dev.allstak.kafka.AllStakKafkaProducerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Appends the AllStak Kafka interceptor class names to every Spring Kafka
 * {@link DefaultKafkaProducerFactory} / {@link DefaultKafkaConsumerFactory}
 * bean so producers stamp trace headers and consumers harvest them — with no
 * per-template configuration.
 *
 * <p>The AllStak interceptor class name is <em>appended</em> to any existing
 * {@code interceptor.classes} value, never replacing the user's own
 * interceptors, and is skipped if already present so a re-processed factory
 * never registers it twice.
 */
public class AllStakKafkaFactoryPostProcessor implements BeanPostProcessor, Ordered {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        try {
            if (bean instanceof DefaultKafkaProducerFactory<?, ?> producerFactory) {
                Map<String, Object> configs = new HashMap<>(producerFactory.getConfigurationProperties());
                Object updated = appendInterceptor(configs.get(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG),
                        AllStakKafkaProducerInterceptor.class.getName());
                if (updated != null) {
                    configs.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, updated);
                    producerFactory.updateConfigs(configs);
                    SdkLogger.debug("AllStak attached Kafka producer interceptor to factory bean '%s'", beanName);
                }
            } else if (bean instanceof DefaultKafkaConsumerFactory<?, ?> consumerFactory) {
                Map<String, Object> configs = new HashMap<>(consumerFactory.getConfigurationProperties());
                Object updated = appendInterceptor(configs.get(ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG),
                        AllStakKafkaConsumerInterceptor.class.getName());
                if (updated != null) {
                    configs.put(ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG, updated);
                    consumerFactory.updateConfigs(configs);
                    SdkLogger.debug("AllStak attached Kafka consumer interceptor to factory bean '%s'", beanName);
                }
            }
        } catch (Exception e) {
            SdkLogger.debug("AllStak Kafka factory instrumentation failed for bean '%s': %s", beanName, e.getMessage());
        }
        return bean;
    }

    /**
     * Returns the new {@code interceptor.classes} value (a comma-joined String)
     * with {@code allStakClass} appended, or {@code null} when it is already
     * present (no update needed). Handles the three shapes Kafka accepts for
     * {@code interceptor.classes}: {@code null}, a comma-separated String, or a
     * {@code List}.
     */
    static Object appendInterceptor(Object existing, String allStakClass) {
        List<String> classes = new ArrayList<>();
        if (existing instanceof String s) {
            for (String part : s.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) classes.add(trimmed);
            }
        } else if (existing instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) classes.add(o.toString());
            }
        }
        if (classes.contains(allStakClass)) {
            return null; // already wired
        }
        classes.add(allStakClass);
        return String.join(",", classes);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
