package github.jcext;


import org.jctools.queues.MpmcArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static github.jcext.JcExt.doneFuture;
import static java.util.Objects.requireNonNull;

/**
 * Enqueuer implements multiple-producer single-consumer pattern, anyone can offer message to the Enqueuer, but only
 * <b>single consumer</b> {@link Poller} can read (poll) it.<p>
 * All operations in this class are non-blocking, and the poller doesn't need a separate thread.<p>
 * Enqueuer is the most basic form of actor-like entity: it is the queue + associated single consumer. <p>
 * All Actor-like entities in this library: {@link TaskEnqueuer} {@link Agent}, use Enqueuer to do their job.
 * @see Poller
 * @param <T>
 */
@SuppressWarnings("WeakerAccess")
public final class Enqueuer<T> {

	private static final Logger log = LoggerFactory.getLogger(Enqueuer.class);
	private static final Executor sameThreadExecutor = Runnable::run;

	private final Queue<T> queue;
	private final AtomicBoolean planned = new AtomicBoolean();
	private final Runnable queuePollRunnable;
	private final String name;
	private final Executor maybeSameThread;

	public static <T> Enqueuer<T> create(Conf conf, Poller<T> poller) {
		return new Enqueuer<>(conf.chooseQueueImpl(), conf.threadPool, conf.name, requireNonNull(poller), conf.sameThreadOpt);
	}

	@SuppressWarnings("unchecked")
	private Enqueuer(Queue<T> q, Executor threadPool, String name, Poller<T> poller,
									 boolean sameThredOpt) {
		this.queue = q;
		this.name = name;
		this.maybeSameThread = sameThredOpt ? sameThreadExecutor : threadPool;

		BiConsumer pollNextIfExists = (ignored, ignored2) -> {
			planned.set(false);
			if (queue.peek() != null) {
				planExecution(threadPool);
			}
		};
		this.queuePollRunnable = () -> runSafely(poller).whenComplete(pollNextIfExists);
	}

	/**
	 * @return false if queue overflow
	 */
	@SuppressWarnings("WeakerAccess")
	public boolean offer(T t) {
		if (!queue.offer(t)) {
			return false;
		}
		planExecution(maybeSameThread);
		return true;
	}

	private void planExecution(Executor exec) {
		if (planned.compareAndSet(false, true)) {
			try {
				exec.execute(queuePollRunnable);
			} catch (Throwable err) {
				// this should never happen
				planned.set(false);
				log.error(errorMessage("uncaught underlying executor error"), err);
			}
		}
	}

	private CompletionStage<?> runSafely(Poller<T> task) {
		try {
			CompletionStage<?> result = task.pollAsync(queue);
			return result != null ? result : doneFuture;
		} catch (Exception e) {
			log.error(errorMessage("uncaught Poller error") , e);
			return doneFuture;
		}
	}

	@Override
	public String toString() {
		return "Enqueuer:" + name;
	}

	private static final Conf defaultConf = new Conf(ForkJoinPool.commonPool(), Conf.defaultCapacity, "", Conf.OptMode.SPEED, true);

	public static Conf conf() {
		return defaultConf;
	}

	public static Conf conf(String name) {
		return conf().name(name);
	}

	/**
	 * Immutable config object
	 */
	public static class Conf {

		private static final int smallCapacity = Integer.parseInt(System.getProperty("jcext.smallCapacity", "256"));
		public static final int defaultCapacity = Integer.parseInt(System.getProperty("jcext.defCapacity", "512"));

		static final CompletableFuture<?> doneFuture = CompletableFuture.completedFuture(null);

		static {
			if (smallCapacity < 16) {
				throw new IllegalStateException("smallCapacity is too small");
			}
			if (defaultCapacity < smallCapacity) {
				throw new IllegalStateException("defaultCapacity is too small");
			}
		}

		public enum OptMode {
			SPEED,
			/**
			 * Use it if you want to avoid pre-allocating the queue with really large capacity.
			 */
			MEMORY
		}

		final Executor threadPool;
		final int capacity;
		final String name;
		final OptMode optimizeFor;
		final boolean sameThreadOpt;


		public Conf threadPool(Executor threadPool) {
			return new Conf(requireNonNull(threadPool), capacity, name, optimizeFor, sameThreadOpt);
		}

		public Conf capacity(int capacity) {
			if (capacity <= 0) {
				throw new IllegalArgumentException();
			}
			return new Conf(threadPool, capacity, name, optimizeFor, sameThreadOpt);
		}

		public Conf unbounded() {
			return new Conf(threadPool, 0, name, optimizeFor, sameThreadOpt);
		}

		public Conf name(String name) {
			return new Conf(threadPool, capacity, requireNonNull(name), optimizeFor, sameThreadOpt);
		}

		public Conf optimizeFor(OptMode optimizeFor) {
			return new Conf(threadPool, capacity, name, requireNonNull(optimizeFor), sameThreadOpt);
		}

		// experimental
		public Conf disableSameThreadOpt() {
			return new Conf(threadPool, capacity, name, requireNonNull(optimizeFor), false);
		}

		<T> Queue<T> chooseQueueImpl() {

			if (optimizeFor == OptMode.MEMORY) {
				if (capacity == 0) {
					throw new IllegalArgumentException("Can't optimize unbounded queue for memory.");
				}
				if (capacity < smallCapacity) {
					throw new IllegalArgumentException("Size of bounded queue is too small to optimize it for memory.");
				}
			}
			if (capacity > 0) { // bounded case
				return optimizeFor == OptMode.SPEED ? new MpmcArrayQueue<>(capacity) : new LinkedBlockingQueue<>(capacity);
			} else { // unbounded case:
				return new ConcurrentLinkedQueue<>();
			}
		}

		private Conf(Executor threadPool, int capacity, String name, OptMode optimizeFor, boolean sameThreadOpt) {
			this.threadPool = threadPool;
			this.capacity = capacity;
			this.name = name;
			this.optimizeFor = optimizeFor;
			this.sameThreadOpt = sameThreadOpt;
		}
	}

	/**
	 * Single consumer (ala poller) of the Enqueuer items.
	 * {@link Enqueuer} guarantees that {@link #pollAsync(Queue)} method will never run concurrently.
	 * @param <T>
	 */
	public interface Poller<T> {
		/**
		 *   This method will be scheduled for execution, only if the queue is not empty.
		 * <p>
		 * => At least one queue.poll() must return non-null inside this method body.
		 * <p>
		 *    This method will be never called concurrently, UNTIL its resulting CompletionStage is completed.
		 * It must be non-blocking. <p>
		 * It may return null, which is interpreted as {@link java.util.concurrent.CompletableFuture#completedFuture(Object)}
		 */
		CompletionStage<?> pollAsync(Queue<T> queue);


		static <T> Poller<T> pollByOne(Function<T, CompletionStage<?>> receiverFun) {
			requireNonNull(receiverFun);
			return q -> receiverFun.apply(q.poll());
		}

		static <T> Poller<T> pollByChunk(int maxChunkSize, Function<List<T>, CompletionStage<?>> receiverFun) {
			if (maxChunkSize <= 0) throw new IllegalArgumentException();
			requireNonNull(receiverFun);
			ArrayList<T> chunk = new ArrayList<>();
			return q -> {
				chunk.clear();
				T item;
				while ((item = q.poll()) != null) {
					chunk.add(item);
					if (chunk.size() >= maxChunkSize) {
						break;
					}
				}
				return receiverFun.apply(chunk);
			};
		}
	}

	private  String errorMessage(String err) {
		return toString() + " : " + err;
	}
}
