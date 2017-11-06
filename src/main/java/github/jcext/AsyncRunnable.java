package github.jcext;

import java.util.concurrent.CompletionStage;

/**
 * AsyncRunnable is considered complete when the resultant CompletionStage is complete.
 * (unlike normal Runnable, which completes when its run() method returns).
 */
@FunctionalInterface
public interface AsyncRunnable {
	/**
	 * Must be non-blocking.
	 * It may return null, which is interpreted the same as {@link java.util.concurrent.CompletableFuture#completedFuture(Object)} (immediate completion)
	 */
	CompletionStage<?> runAsync();

}
