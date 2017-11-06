package github.jcext;


import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface AsyncCallable<T> {
	/**
	 * Must be non-blocking
	 */
	CompletionStage<T> callAcync();
}
