package io.github.actorish4j;

import io.github.actorish4j.internal.ActorishUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;


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
public final class TaskEnqueuer extends Enqueuer<AsyncRunnable> {
	private static final Logger log = LoggerFactory.getLogger(TaskEnqueuer.class);
	private final RejectsListener rejectsListener;


	public static Conf newConf() {
		return new Conf();
	}

	public TaskEnqueuer(Conf config) {
		super(config);
		this.rejectsListener = config.rejectsListener;
	}

	public TaskEnqueuer() {
		this(defaultConfig);
	}

	/**
	 * This form of constructor can save you a few lines of code: you don't need to create configuration\ object yourself.
	 */
	public TaskEnqueuer(Consumer<Conf> configInit) {
		this(ActorishUtil.with(newConf(), configInit));
	}

	/**
	 * Be careful this method will throw RejectedExecutionException, if queue overflows
	 *
	 * @see Enqueuer#offer(Object)   if you want boolean result instead of RejectedExecutionException
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
				return ActorishUtil.doneFuture;
			}
		};
		return offer(t) ? Optional.of(result) : Optional.empty();
	}

	/**
	 * Be careful this method will throw RejectedExecutionException, if queue overflows
	 *
	 * @see #offerCall(AsyncCallable)  if you don't like exceptions.
	 */
	public <V> CompletionStage<V> mustOfferCall(AsyncCallable<V> ac) throws RejectedExecutionException {
		return offerCall(ac).orElseThrow(() -> {
			callRejectListener();
			return new RejectedExecutionException(toString());
		});
	}

	private void callRejectListener() {
		try {
			rejectsListener.onReject(associatedId());
		} catch (Exception ex) {
			log.debug("RejectsListener should never throw exceptions", ex);
		}
	}

	@Override
	protected CompletionStage<?> pollAsync(Queue<AsyncRunnable> queue) {
		return queue.poll().runAsync();
	}

	/**
	 * This hook may be helpful if you need to count how many times RejectedExecutionException was thrown
	 * in mustOffer-methods of {@link TaskEnqueuer}.
	 */
	@FunctionalInterface
	public interface RejectsListener {

		void onReject(Object id);
	}

	private static final RejectsListener emptyListener = id -> {
	};

	private static final Conf defaultConfig = new Conf();
	/**
	 * {@inheritDoc}
	 */
	public static class Conf extends Enqueuer.Conf {
		private RejectsListener rejectsListener = emptyListener;


		/**
		 * For logging/monitoring usage only.
		 */
		public void setRejectsListener(RejectsListener rejectsListener) {
			this.rejectsListener = Objects.requireNonNull(rejectsListener);
		}
	}
}
