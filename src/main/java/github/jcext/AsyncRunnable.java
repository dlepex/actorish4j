package github.jcext;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface AsyncRunnable {
	/**
	 * Must be non-blocking
	 * It may return null, which is interpreted as {@link java.util.concurrent.CompletableFuture#completedFuture(Object)}
	 */
	CompletionStage<?> runAsync();

}
