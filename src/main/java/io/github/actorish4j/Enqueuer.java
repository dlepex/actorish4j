package io.github.actorish4j;


import io.github.actorish4j.internal.ActorishUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Enqueuer implements multiple-producer single-consumer pattern, anyone can offer message to the Enqueuer, but only the
 * <b>single consumer</b> {@link #pollAsync(Queue)} can read (poll) the queue.<p>
 * All operations in this class are non-blocking, so it doesn't need a separate thread.<p>
 * From the user point of view Enqueuer has only one method: {@link #offer(Object)}, this method is thread-safe. <p>
 * <p>
 * Descendants of this class must implement {@link #pollAsync(Queue)} method. <p>
 * If you prefer lambdas instead of subclassing see {@link Poller#newEnqueuer(Poller, Conf)}
 * <p>
 * Only offer() and isEmpty() methods of the {@link Queue} interface are used inside this class.
 * <p>
 *
 * @param <T> type of queue items
 * @see Poller#newEnqueuer(Poller, Conf)
 */
@SuppressWarnings("WeakerAccess")
public abstract class Enqueuer<T> extends EnqueuerBasedEntity {

	private static final int smallCapacity = Integer.getInteger("jcext.enq.smallCap", 65);


	private static final Logger log = LoggerFactory.getLogger(Enqueuer.class);
	private static final Executor sameThreadExecutor = Runnable::run;

	private final Queue<T> queue;
	private final AtomicBoolean planned = new AtomicBoolean();
	private final Runnable queuePollRunnable;
	private final Object id;
	private final Executor maybeSameThread;
	private final Executor threadPool;

	protected Enqueuer(Conf config) {
		this(config.chooseQueueImpl(), config.threadPool, config.id, config.sameThreadOpt);
	}

	@SuppressWarnings("unchecked")
	private Enqueuer(Queue<T> q, Executor threadPool, Object id, boolean sameThredOpt) {
		this.queue = q;
		this.id = id;
		this.maybeSameThread = sameThredOpt ? sameThreadExecutor : threadPool;
		this.threadPool = threadPool;

		BiConsumer pollNextIfExists = (ignored, ignored2) -> {
			planned.set(false);
			if (!queue.isEmpty()) {
				planExecution(threadPool);
			}
		};
		this.queuePollRunnable = () -> doPollAsync().whenComplete(pollNextIfExists);
	}


	/**
	 * This method just calls {@link Queue#offer(Object)}, and possibly schedules {@link #pollAsync(Queue)} execution.
	 *
	 * @return what {@link Queue#offer(Object)} returns, {@code false} means queue overflow for bounded queues. <p>
	 * @see Queue#offer(Object)
	 */
	@SuppressWarnings("WeakerAccess")
	public final boolean offer(T t) {
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

	private CompletionStage<?> doPollAsync() {
		try {
			CompletionStage<?> result = pollAsync(queue);
			return result != null ? result : ActorishUtil.doneFuture;
		} catch (Exception e) {
			log.error(errorMessage("uncaught pollAsync error"), e);
			return ActorishUtil.doneFuture;
		}
	}

	/**
	 * This method will be scheduled for execution, only if the queue is not empty.
	 * It means that at least one queue.poll() must return non-null.
	 * <p>
	 * This method will NOT be called, UNTIL the resultant CompletionStage of the previous call is completed.
	 * This property ensures that <b>concurrent (parallel) calls of this method are impossible</b>.
	 * And also that calls respect <b>happens before</b> relation, so you can freely mutate a shared state inside this method without extra locking.
	 * <p>
	 * This method must be non-blocking. <p>
	 * It may return null, which is interpreted the same as {@link java.util.concurrent.CompletableFuture#completedFuture(Object)} (immediate completion)
	 * <p>
	 * Never save the reference to the queue parameter anywhere, use it only inside the async computation of this method.
	 * i.e. you should not keep/use the queue after the resultant CompletionStage is completed
	 * <p>
	 * You are free to call other methods of the queue in addition to poll().
	 * <p>
	 */
	protected abstract CompletionStage<?> pollAsync(Queue<T> queue);


	private String errorMessage(String err) {
		return toString() + " : " + err;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final Object associatedId() {
		return id;
	}

	@Override
	protected final Enqueuer<?> underlyingEnq() {
		return this;
	}

	public final Executor threadPool() {
		return threadPool;
	}

	private static final Conf defaultConfig = new Conf();

	/**
	 * Configuration object.
	 * Most users will use either {@link #setBoundedQueue(int)} or {@link #setUnboundedQueue()} method <p>
	 * <b>By default the queue is unbounded</b><p>
	 */
	public static class Conf {


		/**
		 * @param capacity max queue size.
		 */
		public void setBoundedQueue(int capacity) {
			if (capacity <= 0) {
				throw new IllegalArgumentException();
			}
			if (capacity < smallCapacity) {
				usePreallocatedQueue = true;
			}
			this.capacity = capacity;

		}

		/**
		 * Be careful and <b>prefer bounded queues</b> for the cases where you deal with external untrusted user input (heap overflow exploit).<p>
		 * Unbounded queues are the right choice when you can't afford item (or event) loss because it will lead to the state corruption
		 * of your actor-like entity. However such entities should not be directly available to the untrusted party.
		 */
		public void setUnboundedQueue() {
			this.capacity = 0;
		}

		/**
		 * Optional user associated id, it is used for exception logging, and in toString()
		 *
		 * @param id must have good readable toString() representation
		 */
		public void setAssociatedId(Object id) {
			this.id = Objects.requireNonNull(id);
		}

		/**
		 * Default pool is FJP.
		 */
		public void setThreadPool(Executor threadPool) {
			this.threadPool = Objects.requireNonNull(threadPool);
		}

		/**
		 * Queue choice tweak for bounded queues <p>
		 *
		 * Use this tweak if you really need array-based (non-shrinkable!) queue. By default, bounded queue is {@link LinkedBlockingQueue},
		 * with this option on it will be {@link ArrayBlockingQueue}. If JCTools found on classpath then MpmcAQ will be used  instead of JDK ABQ. <p>
		 * This tweak is auto-enabled if the setBoundedQueue capacity is less than {@link #smallCapacity} <p>
		 */
		public void usePreallocatedQueue() {
			this.usePreallocatedQueue = true;
		}

		/**
		 * Most users should be happy with default queues, but if you need something very special... <p>
		 * The queue must be thread-safe and define these 3 methods: poll(), offer() and isEmpty(). <p>
		 * Do not use clever lock-free structures. They are not for you.
		 * For instance, most JCTools queues will not work with this library.<p>
		 * Use this method only if you understand what you're doing! <p>
		 */
		public <T> void setCustomQueue(QueueFactory<T> custom) {
			this.custom = Objects.requireNonNull(custom);
		}

		/**
		 * @see #setCustomQueue(QueueFactory)
		 */
		public void setCustomQueue(Supplier<Queue<?>> custom) {
			Objects.requireNonNull(custom);
			setCustomQueue(ignored -> custom.get());
		}

		/**
		 * Use this method if you'd like to gather some stats e.g. max/mean q length, item arrival frequency etc.
		 * Queue wrapper creates the queue proxy, which must proxy at least these 3 methods: poll(), offer() and isEmpty()
		 */
		@SuppressWarnings("all")
		public <T> void setQueueWrapper(UnaryOperator<Queue<T>> wrapper) {
			this.wrapper = (UnaryOperator) Objects.requireNonNull(wrapper);
		}

		/**
		 * Most users should not be concerned with what this option does, the rest may read the code.
		 */
		public void disableSameThreadOptimization() {
			this.sameThreadOpt = false;
		}

		static {
			if (smallCapacity < 16) {
				throw new IllegalStateException("smallCapacity is too small, should be at least 16");
			}
		}


		private Executor threadPool = ForkJoinPool.commonPool();
		private int capacity;
		private Object id;
		private boolean usePreallocatedQueue;
		private boolean sameThreadOpt = true;
		private QueueFactory custom;
		private UnaryOperator<Queue> wrapper;

		<T> Queue<T> chooseQueueImpl() {
			QueueFactory custom = this.custom;
			int cap = this.capacity;
			if (custom != null) return wrap(Objects.requireNonNull(custom.create(cap), "custom queue can't be null."));
			boolean usePreallocatedQueue = this.usePreallocatedQueue;

			if (usePreallocatedQueue && cap == 0) {
				throw new IllegalArgumentException("Preallocated unbounded queue is impossible.");
			}
			if (cap > 0) { // bounded case
				return wrap(usePreallocatedQueue ? ActorishUtil.newPreallocatedQueue(cap) : new LinkedBlockingQueue<>(cap));
			} else { // unbounded case:
				return wrap(new ConcurrentLinkedQueue<>());
			}
		}

		@SuppressWarnings("all")
		private <T> Queue<T> wrap(Queue q) {
			return wrapper == null ? q : Objects.requireNonNull(wrapper.apply(q), "queue wrapper can't return null");
		}
	}


	public interface QueueFactory<T> {
		/**
		 * @param cap bounded queue capacity, if 0 - queue is unbounded
		 */
		Queue<T> create(int cap);
	}
}
