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
import static java.util.Objects.requireNonNull;

/**
 * Enqueuer implements multiple-producer single-consumer pattern, anyone can offer message to the Enqueuer, but only
 * <b>single consumer</b> {@link Poller} can read (poll) it.<p>
 * All operations in this class are non-blocking, and the poller doesn't need a separate thread.<p>
 * From the user point of view this class has only one method: {@link #offer(Object)} <p>
 * <p>
 * Only poll(), offer() and isEmpty() methods of the {@link Queue} interface are used in this class. <p>
 * <p>
 * This class may be inherited, if its {@link #offer(Object)} final method makes sense for the descendants.
 * In other cases prefer composition <p>
 *
 * @param <T> type of queue items
 * @see Poller
 */
@SuppressWarnings("WeakerAccess")
public class Enqueuer<T> extends EnqueuerBasedEntity {
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
	 */
	public static Conf newConf() {
		return new Conf();
	}

	/**
	 * Main constructor, descendant classes must use this form.
	 */
	public Enqueuer(Poller<T> poller, Conf config) {
		this(config.chooseQueueImpl(), config.threadPool, config.id, requireNonNull(poller), config.sameThreadOpt);
	}

	/**
	 * Additional Enqueuer constructor
	 * The constructor with default configuration: FJP thread pool and bounded queue with {@link Enqueuer#defaultCapacity}.
	 */
	@SuppressWarnings("all")
	public Enqueuer(Enqueuer.Poller<T> poller) {
		this(poller, Enqueuer.Conf.Default);
	}

	/**
	 * Additional Enqueuer constructor.
	 * This form of constructor can save you a few lines of code: you don't need to create {@link Enqueuer.Conf} object yourself.
	 */
	public Enqueuer(Enqueuer.Poller<T> poller, Consumer<Enqueuer.Conf> configInit) {
		this(poller, JcExt.with(Enqueuer.newConf(), configInit));
	}

	@SuppressWarnings("unchecked")
	private Enqueuer(Queue<T> q, Executor threadPool, Object id, Poller<T> poller, boolean sameThredOpt) {
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
	 * This method just calls {@link Queue#offer(Object)}, and possibly schedules {@link Poller#pollAsync(Queue)} execution.
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
	 * Asynchronous single consumer of enqueued items.
	 *
	 * @param <T>
	 */
	@FunctionalInterface
	public interface Poller<T> {
		/**
		 * This method will be scheduled for execution, only if the queue is not empty.
		 * It means that at least one queue.poll() must return non-null.
		 * <p>
		 * This method will NOT be scheduled, UNTIL the resultant CompletionStage of the previous call is completed.
		 * This property ensures that concurrent (parallel) polls are impossible and that the poller is really a SINGLE consumer. <p>
		 * This method must be non-blocking. <p>
		 * It may return null, which is interpreted the same as {@link java.util.concurrent.CompletableFuture#completedFuture(Object)} (immediate completion)
		 * <p>
		 * Never save the reference to the queue parameter anywhere, use it only inside async computation of this method.
		 * i.e. you should not keep/use the queue after the resultant CompletionStage is completed
		 */
		CompletionStage<?> pollAsync(Queue<T> queue);


		/**
		 * Creates Poller that polls by one.
		 */
		static <T> Poller<T> pollByOne(Function<T, CompletionStage<?>> receiverFun) {
			requireNonNull(receiverFun);
			return q -> receiverFun.apply(q.poll());
		}

		/**
		 * Creates Poller that polls by chunk.
		 */
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
	public final Object associatedId() {
		return id;
	}

	@Override
	protected final Enqueuer<?> underlyingEnq() {
		return this;
	}


	/**
	 * Configuration object.
	 * <p>
	 * In theory this class should have type argument, but typed config objects feels like an overkill.
	 */
	public static class Conf {

		static final Conf Default = new Conf();

		/**
		 * By default queue is bounded with max size equal to {@link #defaultCapacity}
		 *
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
		 * This option is discouraged, most users should use bounded queues.
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
		 * Queue choice tweak <p>
		 * This option is auto-enabled if the setBoundedQueue capacity is less than {@link #smallCapacity};
		 * It makes no sense for unbounded queue case.
		 */
		public void usePreallocatedQueue() {
			this.usePreallocatedQueue = true;
		}

		/**
		 * Queue choice tweak <p>
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
