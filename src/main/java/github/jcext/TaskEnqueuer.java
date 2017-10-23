package github.jcext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.*;


/**
 * TaskEnqueuer is the {@link Enqueuer} that polls and executes async tasks {@link AsyncRunnable} one by one
 * <p>
 * TaskEnqueuer guarantees that
 * <ul><li> Async tasks will be executed in the order of their arrival
 * <li> Async tasks will NEVER run concurrently i.e. next AsyncRunnable will wait for the completion of the CompletionStage of the previous AsyncRunnable
 * </ul>
 * <p>
 * TaskEnqueuer can be used as the direct replacement for the single-threaded ExecutorService, if you tasks are asynchronous computations. <p>
 * This class doesn't follow ExecutorService submit()/execute() API deliberately because it can be misused for blocking tasks.
 */
@SuppressWarnings("WeakerAccess")
public final class TaskEnqueuer {


	public static TaskEnqueuer create(Enqueuer.Conf c) {
		return new TaskEnqueuer(c);
	}

	private static final Logger log = LoggerFactory.getLogger(TaskEnqueuer.class);
	private final Enqueuer<AsyncRunnable> enq;
	private final String name;

	@SuppressWarnings("unchecked")
	private TaskEnqueuer(Enqueuer.Conf conf) {
		this.enq = Enqueuer.create(conf, q -> q.poll().runAsync());
		this.name = conf.name;
	}

	/**
	 * @return false if queue overflow
	 */
	@SuppressWarnings("WeakerAccess")
	public boolean offer(AsyncRunnable task) {
		return enq.offer(task);
	}

	/**
	 * @see #offer(AsyncRunnable)  if you want boolean result instead of RejectedExecutionException
	 * @see #offerCall(AsyncCallable)  if your task has some usefull result of its execution
	 */
	public void mustOffer(AsyncRunnable task) throws RejectedExecutionException {
		if (!offer(task)) {
			throw new RejectedExecutionException(toString());
		}
	}

	/**
	 * @return Optional.empty if queue overflow.
	 */
	@SuppressWarnings("WeakerAccess")
	public <V> Optional<CompletionStage<V>> offerCall(AsyncCallable<V> ac) {
		CompletableFuture<V> result = new CompletableFuture<>();
		AsyncRunnable t = () -> {
			try {
				CompletionStage<V> acResult = ac.callAcync();
				acResult.whenComplete((value, ex) -> {
					if (ex == null) {
						result.complete(value);
					} else {
						result.completeExceptionally(ex);
					}
				});
				return acResult;
			} catch (Exception e) {
				result.completeExceptionally(e);
				return JcExt.doneFuture;
			}
		};
		return offer(t) ? Optional.of(result) : Optional.empty();
	}

	public <V> CompletionStage<V> mustOfferCall(AsyncCallable<V> ac) throws RejectedExecutionException {
		return offerCall(ac).orElseThrow(() -> new RejectedExecutionException(toString()));
	}


	@Override
	public String toString() {
		return "TaskEnqueuer:" + name;
	}
}
