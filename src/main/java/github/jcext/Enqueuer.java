package github.jcext;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

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
 * @param <T>
 * @see Poller
 */
@SuppressWarnings("WeakerAccess")
public final class Enqueuer<T> extends EnqueuerStats {

	public static final int defaultCapacity = Integer.getInteger("jcext.enq.defaultCap", 8192);
	private static final int smallCapacity = Integer.getInteger("jcext.enq.smallCap", 65);


	private static final Logger log = LoggerFactory.getLogger(Enqueuer.class);
	private static final Executor sameThreadExecutor = Runnable::run;

	private final Queue<T> queue;
	private final AtomicBoolean planned = new AtomicBoolean();
	private final Runnable queuePollRunnable;
	private final Object id;
	private final Executor maybeSameThread;

	public static Conf newConf() {
		return new Conf();
	}

	public static <T> Enqueuer<T> create(Poller<T> poller) {
		return create(poller, newConf());
	}

	public static <T> Enqueuer<T> create(Poller<T> poller, Consumer<Conf> confInit) {
		return create(poller, with(newConf(), confInit));
	}

	public static <T> Enqueuer<T> create(Poller<T> poller, Conf c) {
		return new Enqueuer<>(c.chooseQueueImpl(), c.threadPool, c.id, requireNonNull(poller), c.sameThreadOpt);
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
			log.error(errorMessage("uncaught Poller error"), e);
			return doneFuture;
		}
	}


	public static class Conf {

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
		 * The queue is bounded by default: see {@link #defaultCapacity}.
		 * And you should prefer bounded queues.
		 */
		public void setUnboundedQueue() {
			this.capacity = 0;
		}

		public void setId(Object id) {
			this.id = Objects.requireNonNull(id);
		}

		public void setThreadPool(Executor threadPool) {
			this.threadPool = Objects.requireNonNull(threadPool);
		}

		/**
		 * This option is auto-enabled if the setBoundedQueue capacity is less than {@link #smallCapacity};
		 * It makes no sense for unbounded queue case.
		 */
		public void usePreallocatedQueue() {
			this.usePreallocatedQueue = true;
		}

		/**
		 * Unbounded queue case is always lock-free, it is based on CLQ.
		 * Bounded queue case is based on LBQ or ABQ (if usePreallocatedQueue)  by default.
		 *
		 * If you want bounded queue to be lock-free you must
		 *  - enable options: usePreallocatedQueue() and useLockFreeQueue()
		 *  - include org.jctools:jctools-core:2.1.1+ dependency
		 */
		public void useLockFreeQueue() {
			this.useLockFreeQueue = true;
		}

		/**
		 * Use this method only if you understand what you're doing.
		 */
		public void setCustomQueueCreator(QueueCreator c) {
			this.custom = Objects.requireNonNull(c);
		}


		public void disableSameThreadOptimization() {
			this.sameThreadOpt = false;
		}

		static {
			if (smallCapacity < 16) {
				throw new IllegalStateException("smallCapacity is too small");
			}
			if (defaultCapacity < smallCapacity) {
				throw new IllegalStateException("defaultCapacity is too small");
			}
		}


		private Executor threadPool = ForkJoinPool.commonPool();
		private int capacity = defaultCapacity;
		private Object id;
		private boolean useLockFreeQueue;
		private boolean usePreallocatedQueue;
		private boolean sameThreadOpt = true;
		private QueueCreator custom;


		public void setFrom(Conf c) {
			this.sameThreadOpt = c.sameThreadOpt;
			this.useLockFreeQueue = c.useLockFreeQueue;
			this.threadPool = c.threadPool;
			this.capacity = c.capacity;
			this.id = c.id;
			this.usePreallocatedQueue = c.usePreallocatedQueue;
			this.custom = c.custom;
		}

		protected Conf() {
		}

		<T> Queue<T> chooseQueueImpl() {
			QueueCreator custom = this.custom;
			boolean usePreallocatedQueue = this.usePreallocatedQueue;
			int cap = capacity;

			if (custom != null) return custom.create(cap);

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
				return usePreallocatedQueue ? JcExt.newPreallocatedQueue(cap) : new LinkedBlockingQueue<>(cap);
			} else { // unbounded case:
				return new ConcurrentLinkedQueue<>();
			}
		}

	}

	/**
	 * Single consumer (ala poller) of the Enqueuer items.
	 * {@link Enqueuer} guarantees that {@link #pollAsync(Queue)} method will never run concurrently.
	 *
	 * @param <T>
	 */
	@FunctionalInterface
	public interface Poller<T> {
		/**
		 * This method will be scheduled for execution, only if the queue is not empty.
		 * <p>
		 * => At least one queue.poll() must return non-null.
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
	 * User associated id
	 */
	@Override
	public Object id() {
		return id;
	}

	/**
	 * can be used for stats/monitoring
	 */
	@Override
	public int queueSize() {
		return queue.size();
	}

	@Override
	Enqueuer<?> enq() {
		return this;
	}


	public interface QueueCreator {
		/**
		 * The queue must define at least 3 methods: poll(), offer() and isEmpty()
		 * The queue must be thread-safe. The easiest way to achieve this is by using lock (synchronized block etc).
		 * Do not use clever lock-free structures. They are not for you:
		 * for instance most JCTools queues will not work with this library!
		 *
		 * @param cap if 0 -> queue is unbounded.
		 */
		<T> Queue<T> create(int cap);
	}
}
