package github.jcext;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

import static github.jcext.JcExt.doneFuture;
import static github.jcext.JcExt.with;
import static java.util.Objects.requireNonNull;

/**
 * Enqueuer implements multiple-producer single-consumer pattern, anyone can offer message to the Enqueuer, but only
 * <b>single consumer</b> {@link Poller} can read (poll) it.<p>
 * All operations in this class are non-blocking, and the poller doesn't need a separate thread.<p>
 * Enqueuer is the most basic form of actor-like entity: it is the queue + associated single consumer. <p>
 * All Actor-like entities in this library: {@link TaskEnqueuer} {@link Agent}, use Enqueuer to do their job <p>
 * <p>
 * Only poll(), offer() and isEmpty() methods of {@link Queue} are used throughout this library.
 *
 * @param <T> type of queue items
 * @see Poller
 */
@SuppressWarnings("WeakerAccess")
public final class Enqueuer<T> extends EnqueuerBasedEntity {
	/**
	 * Default bounded queue capacity.
	 * Use system property {@code "jcext.enq.defaultCap"} to change it.
	 * Default value is 8192
	 */
	public static final int defaultCapacity = Integer.getInteger("jcext.enq.defaultCap", 8192);
	private static final int smallCapacity = Integer.getInteger("jcext.enq.smallCap", 65);


	private static final Logger log = LoggerFactory.getLogger(Enqueuer.class);
	private static final Executor sameThreadExecutor = Runnable::run;

	private final Queue<T> queue;
	private final AtomicBoolean planned = new AtomicBoolean();
	private final Runnable queuePollRunnable;
	private final Object id;
	private final Executor maybeSameThread;


	/**
	 * Creates new config object with default settings.
	 *
	 * @see Enqueuer#create(Poller, Consumer)
	 */
	public static <T> Conf<T> newConf() {
		return new Conf<>();
	}

	/**
	 * The constructor with default configuration: FJP thread pool and bounded queue with {@link Enqueuer#defaultCapacity}.
	 */
	@SuppressWarnings("all")
	public static <T> Enqueuer<T> create(Poller<T> poller) {
		return create(poller, Conf.Default);
	}

	/**
	 * This form of constructor can save you a few lines of code: you don't need to create {@link Conf} object yourself.
	 */
	public static <T> Enqueuer<T> create(Poller<T> poller, Consumer<Conf<T>> configInit) {
		return create(poller, with(newConf(), configInit));
	}

	public static <T> Enqueuer<T> create(Poller<T> poller, Conf<T> config) {
		return new Enqueuer<>(config.chooseQueueImpl(), config.threadPool, config.id, requireNonNull(poller), config.sameThreadOpt);
	}

	@SuppressWarnings("unchecked")
	private Enqueuer(Queue<T> q, Executor threadPool, Object id, Poller<T> poller,
									 boolean sameThredOpt) {
		this.queue = q;
		this.id = id;
		this.maybeSameThread = sameThredOpt ? sameThreadExecutor : threadPool;

		BiConsumer pollNextIfExists = (ignored, ignored2) -> {
			planned.set(false);
			if (!queue.isEmpty()) {
				planExecution(threadPool);
			}
		};
		this.queuePollRunnable = () -> runSafely(poller).whenComplete(pollNextIfExists);
	}

	/**
	 * @return false if queue overflows
	 * @see Queue#offer(Object)
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
			log.error(errorMessage("uncaught Poller error"), e);
			return doneFuture;
		}
	}


	/**
	 * Asynchronous single consumer of queued items.
	 * {@link Enqueuer} guarantees that {@link #pollAsync(Queue)} method will never run concurrently.
	 *
	 * @param <T>
	 */
	@FunctionalInterface
	public interface Poller<T> {
		/**
		 * This method will be scheduled for execution, only if the queue is not empty.
		 * At least one queue.poll() must return non-null.
		 * <p>
		 * This method will be never called concurrently, UNTIL its resultant CompletionStage is completed.
		 * It must be non-blocking. <p>
		 * It may return null, which is interpreted as {@link java.util.concurrent.CompletableFuture#completedFuture(Object)}
		 * <p>
		 * Never save the reference to the queue parameter anywhere, use it only inside async computation of this method.
		 * (i.e. you should not keep/use the queue after the resultant CompletionStage is completed)
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

	private String errorMessage(String err) {
		return toString() + " : " + err;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Object id() {
		return id;
	}

	@Override
	protected Enqueuer<?> underlyingEnq() {
		return this;
	}


	/**
	 * Configuration object.
	 *
	 * @param <T> type of queue items
	 */
	public static class Conf<T> {

		private static final Conf Default = new Conf();

		/**
		 * By default queue is bounded with max size equal to {@link #defaultCapacity}
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
		 * This option is discouraged. Most users should use bounded queues.
		 */
		public void setUnboundedQueue() {
			this.capacity = 0;
		}

		/**
		 * Optional user associated id.
		 *
		 * @param id must have good readable toString() representation
		 */
		public void setId(Object id) {
			this.id = Objects.requireNonNull(id);
		}

		/**
		 * Default pool is FJP.
		 */
		public void setThreadPool(Executor threadPool) {
			this.threadPool = Objects.requireNonNull(threadPool);
		}

		/**
		 * Queue choice option <p>
		 * This option is auto-enabled if the setBoundedQueue capacity is less than {@link #smallCapacity};
		 * It makes no sense for unbounded queue case.
		 */
		public void usePreallocatedQueue() {
			this.usePreallocatedQueue = true;
		}

		/**
		 * Queue choice option <p>
		 * Unbounded queue case is always lock-free, it is based on CLQ.
		 * Bounded queue case is based on LBQ or ABQ (ABQ if usePreallocatedQueue).
		 * <p>
		 * If you want bounded queue to be lock-free you must
		 * <ul><li> enable options: usePreallocatedQueue() and useLockFreeQueue()
		 * <li> include dependency: org.jctools:jctools-core:2.1.1+ dependency</ul>
		 */
		public void useLockFreeQueue() {
			this.useLockFreeQueue = true;
		}

		/**
		 * The queue must be thread-safe and define these 3 methods: poll(), offer() and isEmpty(). <p>
		 * Do not use clever lock-free structures. They are not for you.
		 * For instance, most JCTools queues will not work with this library.<p>
		 * Use this method only if you understand what you're doing! <p>
		 */
		public void setCustomQueue(QueueFactory<T> custom) {
			this.custom = Objects.requireNonNull(custom);
		}

		/**
		 * @see #setCustomQueue(QueueFactory)
		 */
		public void setCustomQueue(Supplier<? extends Queue<T>> custom) {
			Objects.requireNonNull(custom);
			setCustomQueue(ignored -> custom.get());
		}

		/**
		 * Use this method if you'd like to gather some stats e.g. max/mean q length, item arrival frequency etc.
		 * Queue wrapper creates the queue proxy, which must proxy at least these 3 methods: poll(), offer() and isEmpty()
		 */
		public void setQueueWrapper(UnaryOperator<Queue<T>> wrapper) {
			this.wrapper = Objects.requireNonNull(wrapper);
		}

		/**
		 * Most users should not be concerned with what this option is doing, the rest may read the code.
		 */
		public void disableSameThreadOptimization() {
			this.sameThreadOpt = false;
		}

		static {
			if (smallCapacity < 16) {
				throw new IllegalStateException("smallCapacity is too small, should be at least 16");
			}
			if (defaultCapacity < smallCapacity) {
				throw new IllegalStateException("defaultCapacity is too small, should be > smallCapacity == " + smallCapacity);
			}
		}


		private Executor threadPool = ForkJoinPool.commonPool();
		private int capacity = defaultCapacity;
		private Object id;
		private boolean useLockFreeQueue;
		private boolean usePreallocatedQueue;
		private boolean sameThreadOpt = true;
		private QueueFactory<T> custom;
		private UnaryOperator<Queue<T>> wrapper;

		protected Conf() {
		}

		Queue<T> chooseQueueImpl() {
			QueueFactory<T> custom = this.custom;
			int cap = this.capacity;
			if (custom != null) return wrap(custom.create(cap));
			boolean usePreallocatedQueue = this.usePreallocatedQueue;

			if (usePreallocatedQueue && cap == 0) {
				throw new IllegalArgumentException("Preallocated unbounded queue is impossible.");
			}
			if (cap > 0) { // bounded case
				if (useLockFreeQueue) {
					if (!JcExt.isUsingJcTools()) {
						throw new IllegalStateException("To use lock free bounded queue you must include dependency: org.jctools:jctools-core:2.1.1+");
					}
					if (!usePreallocatedQueue) {
						throw new IllegalStateException("Enable usePreallocatedQueue() option as well if you want lock-free bounded queue.");
					}
				}
				return wrap(usePreallocatedQueue ? JcExt.newPreallocatedQueue(cap) : new LinkedBlockingQueue<>(cap));
			} else { // unbounded case:
				return wrap(new ConcurrentLinkedQueue<>());
			}
		}

		private Queue<T> wrap(Queue<T> q) {
			return wrapper == null ? q : wrapper.apply(q);
		}
	}


	public interface QueueFactory<T> {
		/**
		 *
		 * @param cap bounded queue capacity, if 0 - queue is unbounded.
		 */
		Queue<T> create(int cap);
	}
}
