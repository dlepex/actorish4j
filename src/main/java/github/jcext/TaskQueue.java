package github.jcext;

import com.google.common.base.Preconditions;
import org.jctools.queues.MpmcArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;


/**
 * TaskQueue lets the user to enqueue and execute async tasks and guarantees that they will run sequentially
 * TaskQueue can be used to implement such concurrent entities as Actors or Agents.
 */
@SuppressWarnings("WeakerAccess")
public final class TaskQueue {
	/**
	 * Task is considered completed when its CompletionStage is complete.
	 */
	@FunctionalInterface
	public interface Task {
		/**
		 * Must be non-blocking
		 */
		CompletionStage<?> run();

		static Task runnable(Runnable r) {
			return () -> {
				r.run();
				return DoneFuture;
			};
		}
	}

	@FunctionalInterface
	public interface TaskWithResult<V> {

		CompletionStage<V> run();

	}

	public static class Conf {
		public Executor threadPool = ForkJoinPool.commonPool();
		public int queueCapacity = 0; // less or eq 0 means unlimited
	}

	public static Conf withCapacity(int queueCap) {
		Preconditions.checkArgument(queueCap > 0);
		Conf c = new Conf();
		c.queueCapacity = queueCap;
		return c;
	}

	public static Conf unlimited() {
		return new Conf();
	}

	private static final Logger log = LoggerFactory.getLogger(TaskQueue.class);
	private static final Executor sameThreadExecutor = Runnable::run;

	private final Queue<Task> queue;
	private final AtomicBoolean planned = new AtomicBoolean();
	private final Runnable queuePollRunnable;

	public static TaskQueue create(Conf c) {
		return new TaskQueue(c.threadPool, c.queueCapacity);
	}

	@SuppressWarnings("unchecked")
	private TaskQueue(Executor threadPool, int queueCap) {
		this.queue = queueCap > 0 ? new MpmcArrayQueue<>(queueCap) : new ConcurrentLinkedQueue<>();

		BiConsumer pollNextIfExists = (ignored, ignored2) -> {
			planned.set(false);
			if (queue.peek() != null) {
				planExecution(threadPool);
			}
		};
		this.queuePollRunnable = () -> runSafely(queue.poll()).whenComplete(pollNextIfExists);
	}

	/**
	 * @see #tryEnqueue(Task)  if you want boolean result instead of RejectedExecutionException
	 * @see #tryEnqueueWithResult(TaskWithResult)  if your task has some usefull result of its execution
	 */
	public void enqueue(Task t) throws RejectedExecutionException {
		if (!tryEnqueue(t)) {
			throw new RejectedExecutionException("queue overflow");
		}
	}

	/**
	 * @return false if queue overflow
	 */
	@SuppressWarnings("WeakerAccess")
	public boolean tryEnqueue(Task t) {
		if (!queue.offer(t)) {
			return false;
		}
		planExecution(sameThreadExecutor);
		return true;
	}

	/**
	 * @return Optional.empty if queue overflow.
	 */
	@SuppressWarnings("WeakerAccess")
	public <V> Optional<CompletionStage<V>> tryEnqueueWithResult(TaskWithResult<V> twr) {
		CompletableFuture<V> result = new CompletableFuture<>();
		Task t = () -> {
			try {
				CompletionStage<V> twrResult = twr.run();
				twrResult.whenComplete((value, ex) -> {
					if (ex == null) {
						result.complete(value);
					} else {
						result.completeExceptionally(ex);
					}
				});
				return twrResult; // Task queue doesn't wait for waitComplete() callback above, only for twrResult itself
			} catch (Exception e) {
				result.completeExceptionally(e);
				return DoneFuture;
			}
		};
		return tryEnqueue(t) ? Optional.of(result) : Optional.empty();
	}

	public <V> CompletionStage<V> enqueueWithResult(TaskWithResult<V> twr) throws RejectedExecutionException {
		return tryEnqueueWithResult(twr).orElseThrow(() -> new RejectedExecutionException("queue overflow"));
	}

	private void planExecution(Executor exec) {
		if (planned.compareAndSet(false, true)) {
			try {
				exec.execute(queuePollRunnable);
			} catch (Throwable err) {
				// this should never happen
				planned.set(false);
				log.error("uncaught underlying executor error", err);
			}
		}
	}

	private static final CompletableFuture<Void> DoneFuture = CompletableFuture.completedFuture(null);

	private static CompletionStage<?> runSafely(Task t) {
		try {
			return t.run();
		} catch (Exception e) {
			log.error("uncaught task error", e);
			return DoneFuture;
		}
	}
}
