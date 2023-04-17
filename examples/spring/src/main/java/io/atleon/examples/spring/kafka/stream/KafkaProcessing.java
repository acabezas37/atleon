package io.atleon.examples.spring.kafka.stream;

import io.atleon.core.AloStream;
import reactor.core.Disposable;

public class KafkaProcessing extends AloStream<KafkaProcessingConfig> {

    @Override
    protected Disposable startDisposable(KafkaProcessingConfig config) {
        return config.buildKafkaLongReceiver()
            .receiveAloValues(config.getTopic())
            .filter(this::isPrime)
            .map(primeNumber -> "Found a prime number: " + primeNumber)
            .consume(config.getConsumer())
            .resubscribeOnError(config.name())
            .subscribe();
    }

    private boolean isPrime(Number number) {
        long safeValue = Math.abs(number.longValue());
        for (long i = 2; i <= Math.sqrt(safeValue); i++) {
            if (safeValue % i == 0) {
                return false;
            }
        }
        return safeValue > 0;
    }
}
