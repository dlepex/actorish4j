package io.github.actorish4j;


import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface AsyncCallable<T> {
	/**
	 * Must be non-blocking (usually, but it depends on chosen thread pool in Conf)
	 */
	CompletionStage<T> callAsync();
}
