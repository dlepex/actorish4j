package github.jcext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import static github.jcext.JcExt.with;


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
public final class TaskEnqueuer extends EnqueuerStats {

	public static Conf newConf() {
		return new Conf();
	}

	public static TaskEnqueuer create() {
		return create(newConf());
	}

	public static TaskEnqueuer create(Conf c) {
		return new TaskEnqueuer(c);
	}

	public static TaskEnqueuer create(Consumer<TaskEnqueuer.Conf> confInit) {
		return create(with(newConf(), confInit));
	}

	private static final Logger log = LoggerFactory.getLogger(TaskEnqueuer.class);
	private final Enqueuer<AsyncRunnable> enq;
	private final RejectsListener rejectsListener;

	@SuppressWarnings("unchecked")
	private TaskEnqueuer(Conf c) {
		this.rejectsListener = c.rejectsListener;
		this.enq = Enqueuer.create(q -> q.poll().runAsync(), c);
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
			callRejectListener();
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
		return offerCall(ac).orElseThrow(() -> {
			callRejectListener();
			return new RejectedExecutionException(toString());
		});
	}

	@Override
	public Enqueuer<AsyncRunnable> enq() {
		return enq;
	}

	private void callRejectListener() {
		try {
			rejectsListener.onReject(id());
		} catch (Exception ex) {
			log.debug("RejectsListener should never throw exceptions", ex);
		}
	}


	/**
	 * Hook for gathering stats or logging.
	 */
	@FunctionalInterface
	public interface RejectsListener {
		void onReject(Object id); // this method shouldn't throw exception.
	}

	private static final RejectsListener EmptyListener = id -> {};

	public static class Conf extends Enqueuer.Conf {
		private RejectsListener rejectsListener = EmptyListener;

		// Use only for monitoring/logging.
		public void setRejectsListener(RejectsListener rejectsListener) {
			this.rejectsListener = Objects.requireNonNull(rejectsListener);
		}
	}
}
