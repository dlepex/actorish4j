package github.jcext;


import java.util.concurrent.*;

/**
 * Experimental API, cancellation may be added in the future.
 */
@FunctionalInterface
public interface Timer {

	void delay(Runnable r, long timeout, TimeUnit unit);

	default <T> CompletionStage<T> delayValue(T value, long timeout, TimeUnit unit) {
		CompletableFuture<T> f = new CompletableFuture<>();
		delay(() -> f.complete(value), timeout, unit);
		return f;
	}

	/**
	 * If you use Netty, it's good idea to implement Timer interface on top of HashWheelTimer, so avoid defaultInstance() in that case.
	 */
	static Timer defaultInstance() {
		return DefaultImpl.timer;
	}


	final class DefaultImpl {
		private static ScheduledExecutorService sched = Executors.newScheduledThreadPool(2);
		private static final Timer timer = (r, t, u) -> sched.schedule(r, t, u);
	}


}
