package github.jcext;


import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface AsyncCallable<T> {
	/**
	 * Must be non-blocking (like every method returning CompletionStage/Future).
	 */
	CompletionStage<T> callAcync();
}
