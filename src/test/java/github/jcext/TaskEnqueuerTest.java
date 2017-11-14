package github.jcext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.testng.Assert.*;

@SuppressWarnings({"unused", "WeakerAccess"})
public class TaskEnqueuerTest {

	private static final Logger log = LoggerFactory.getLogger(TaskEnqueuerTest.class);

	@DataProvider
	public Object[][] presets() {
		return new Object[][]{
				{500, 3, 0},
				{1000, 2, 0},
				{600, 8, 0},
				{300, 10, 0},
				{400, 4, 0},
				{1400, 16, 0},
				{500, 3, 0},
				{1000, 2, 0},
				{600, 8, 0},
				{300, 10, 0},
				{400, 4, 0},
				{1400, 16, 0},
				{1400, 16, 0},
				{1400, 16, 0},
				{1400, 16, 0},
				{1400, 16, 0}
		};
	}

	@Test(dataProvider = "presets")
	void testMultipleProducersSummation(int max, int nproducers, int qcapacity) throws Exception {
		final int sum = IntStream.range(0, max).sum();
		if (qcapacity == 0) {
			qcapacity = 2 * nproducers * max + 1;
		}
		int qcap = qcapacity;
		TaskEnqueuer tq = new TaskEnqueuer(c -> {
			c.setBoundedQueue(qcap);
		});
		int[] sumBox = new int[1];
		Function<Integer, AsyncRunnable> incTask = (n) -> delayedTask(() -> {
			int z = rng.nextInt(100);
			sumBox[0] -= z;
			sumBox[0] -= 2 * z;
			sumBox[0] += n;
			sumBox[0] += 3 * z;
		});
		CountDownLatch latch = new CountDownLatch(nproducers);
		AtomicReference<Exception> threadErr = new AtomicReference<>();

		IntStream.range(0, nproducers).mapToObj(tnum -> new Thread(() -> {
			try {
				for (int i = 0; i < max; i++) {
					tq.mustOffer(incTask.apply(i));
					if (rng.nextDouble() < 0.1) {
						tq.mustOffer(() -> {
							throw MyException;
						});
					}
				}
				log.debug("thread finished {}", tnum);
			} catch (Exception e) {
				threadErr.set(e);
				log.error("Thread error", e);
			}
			latch.countDown();
		})).collect(Collectors.toList()).forEach(Thread::start);

		assertTrue(latch.await(5, TimeUnit.SECONDS), "Producers must finish within this timeout!");

		if (threadErr.get() != null) {
			throw threadErr.get();
		}

		CompletableFuture<Integer> cf = new CompletableFuture<>();
		tq.mustOffer(() -> {
			cf.complete(sumBox[0]);
			return CompletableFuture.completedFuture(null);
		});

		assertEquals(cf.get(5, TimeUnit.SECONDS).intValue(), sum * nproducers);
	}

	@Test
	void testRejectectedExecution() {
		assertThrows(RejectedExecutionException.class, () -> testMultipleProducersSummation(1000, 4, 20));
	}

	@Test
	void testResultCalulation() throws Exception {
		TaskEnqueuer tq = new TaskEnqueuer(c -> c.setBoundedQueue(10));
		Optional<CompletionStage<Integer>> opt = tq.offerCall(() -> CompletableFuture.supplyAsync(() -> 10));
		assertTrue(opt.isPresent());
		assertEquals(opt.get().toCompletableFuture().get(), new Integer(10));
	}


	@Test
	void testConf() {
		Enqueuer.Conf c = new Enqueuer.Conf();
		c.setBoundedQueue(10_000);
		assertEquals(c.chooseQueueImpl().getClass(), LinkedBlockingQueue.class);

		c = new Enqueuer.Conf();
		c.setBoundedQueue(10_000);
		c.usePreallocatedQueue();
		assertEquals(c.chooseQueueImpl().getClass(), ArrayBlockingQueue.class);

		c = new Enqueuer.Conf();
		c.setUnboundedQueue();
		assertEquals(c.chooseQueueImpl().getClass(), ConcurrentLinkedQueue.class);
	}

	private static final RuntimeException MyException = new RuntimeException("Not a failure - just test exceptions.") {
		@Override
		public synchronized Throwable fillInStackTrace() {
			return null;
		}
	};

	private ScheduledExecutorService sched = Executors.newScheduledThreadPool(16);
	private static final ThreadLocalRandom rng = ThreadLocalRandom.current();

	private AsyncRunnable delayedTask(Runnable r) {
		return () -> {
			CompletableFuture<Void> cf = new CompletableFuture<>();
			r.run();
			sched.schedule(() -> {
				try {
					cf.complete(null);
				} catch (Exception e) {
					cf.completeExceptionally(e);
				}
			}, rng.nextInt(50), TimeUnit.MICROSECONDS);
			return cf;
		};
	}




}