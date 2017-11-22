package com.github.actorish4j;


import com.github.actorish4j.applications.ExactDateScheduler;
import org.testng.annotations.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;


public class ExactDateSchedulerTest {

	ExactDateScheduler sched = new ExactDateScheduler(c -> {
		c.setPlannedTasksLimit(5000);
		c.setUnboundedQueue();
	});
	ThreadLocalRandom rng = ThreadLocalRandom.current();

	@Test
	public void testSchedulingOrder() throws Exception {
		LocalDateTime now = LocalDateTime.now();
		int maxTasks = 2000;
		List<LocalDateTime> dates = IntStream.range(0, maxTasks)
				.mapToObj(i -> now.plusSeconds(rng.nextInt(1, 20)))
				.collect(Collectors.toList());
		CountDownLatch latch = new CountDownLatch(maxTasks);
		AtomicLong aggregateDelay = new AtomicLong();
		ConcurrentLinkedQueue<ExactDateScheduler.Task.Info> infos = new ConcurrentLinkedQueue<>();
		dates.forEach(begin -> {
			sched.schedule(begin, info -> {
				aggregateDelay.addAndGet(info.delay().toMillis());
				infos.offer(info);
				latch.countDown();
			});
		});
		latch.await();
		ExactDateScheduler.Task.Info info, prev = null;
		System.out.printf("avg_delay_ms=%s total=%s", ((double) aggregateDelay.get()) / maxTasks, aggregateDelay);
		while ((info = infos.poll()) != null) {
			if (prev != null) {
				int cmp = info.begin().compareTo(prev.begin());
				assertTrue(cmp > 0 || (cmp == 0 && info.order() > prev.order()));
			}
			assertTrue(info.delay().toNanos() >= 0);
			prev = info;
		}
	}

	@Test
	public void testCallable() throws Exception {
		CompletableFuture<Integer> f = sched.schedule(LocalDateTime.now().plusSeconds(1), () -> 7);
		assertEquals((int) f.get(), 7);
	}
}