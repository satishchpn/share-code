package com.keshri.resilience4jdemo.messaging;

import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.cloud.stream.binding.BindingsLifecycleController.State;
import org.springframework.cloud.stream.endpoint.BindingsEndpoint;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;

import com.google.gson.Gson;
import com.keshri.resilience4jdemo.channel.MessageChannel;
import com.keshri.resilience4jdemo.model.SimpleMessage;
import com.keshri.resilience4jdemo.service.DemoService;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnFailureRateExceededEvent;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnResetEvent;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import lombok.extern.slf4j.Slf4j;

@EnableBinding(MessageChannel.class)
@Slf4j
public class KafkaMessageConsumer<T> {

	private final CircuitBreaker circuitBreaker;

	private final BindingsEndpoint bindingsEndpoint;

	private DemoService demoService;

	public KafkaMessageConsumer(CircuitBreakerRegistry circuitBreakerRegistry, BindingsEndpoint bindingsEndpoint,
			DemoService demoService) {
		this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("externalService");
		this.bindingsEndpoint = bindingsEndpoint;
		this.demoService = demoService;

		this.circuitBreaker.getEventPublisher().onStateTransition(this::onStateChange);
		this.circuitBreaker.getEventPublisher().onFailureRateExceeded(this::onFailureRateExceeded);
		this.circuitBreaker.getEventPublisher().onReset(this::onReset);

	}

	@StreamListener(MessageChannel.INPUT)
	public void onMessage(final Message<T> message) {
		log.info("Kafka Message Payload Received {}", message.getPayload());
		log.info("Kafka Message Header Received {}", message.getHeaders());
		log.info("CircuitBreaker current state is {}", this.circuitBreaker.getState());
		final Acknowledgment acknowledgment = message.getHeaders().get(KafkaHeaders.ACKNOWLEDGMENT,
				Acknowledgment.class);
		log.info("CircuitBreaker acknowledgment  is {}", acknowledgment);
		SimpleMessage payLoad = new Gson().fromJson(message.getPayload().toString(), SimpleMessage.class);
		if (CircuitBreaker.State.OPEN == this.circuitBreaker.getState()) {
			log.info("CircuitBreaker is in OPEN State");
			acknowledgment.nack(10);
			return;
		}
		demoService.callDownStreamService(payLoad);

		if (acknowledgment != null) {
			acknowledgment.acknowledge();
		}
	}

	private void onStateChange(final CircuitBreakerOnStateTransitionEvent transitionEvent) {
		CircuitBreaker.State state = transitionEvent.getStateTransition().getToState();
		switch (state) {
		case OPEN:
			log.info("CircuitBreaker OPEN State Transition Event Received...");
			bindingsEndpoint.changeState(MessageChannel.INPUT, State.STOPPED);
		case CLOSED:
			log.info("CircuitBreaker CLOSED State Transition Event Received...");
		case HALF_OPEN:
			log.info("CircuitBreaker HALF_OPEN State Transition Event Received...");
			bindingsEndpoint.changeState(MessageChannel.INPUT, State.STARTED);
		}
	}
	
	private void onFailureRateExceeded(final CircuitBreakerOnFailureRateExceededEvent event) {
		log.info("CircuitBreaker CircuitBreakerOnFailureRateExceededEvent with failure rate "+event.getFailureRate());
		event.getFailureRate();
	}
	
	private void onReset(final CircuitBreakerOnResetEvent resetEvent) {
		log.info("CircuitBreaker CircuitBreakerOnResetEvent triggered...");
	}

}
