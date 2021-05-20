package io.github.actorish4j;

import io.github.actorish4j.internal.ActorishUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * This interface provides alternative ways to create {@link Enqueuer} instances i.e.
 * thru lambdas instead of subclassing.
 *
 * Poller also provides handy polling adaptors: {@code pollByOne, pollByChunk} to avoid dealing with queues.
 *
 * @param <T>
 * @see #pollByOne(Function) 
 * @see #pollByChunk
 */
@FunctionalInterface
public interface Poller<T> {

	/**
	 * @see Enqueuer#pollAsync(Queue)
	 */
	CompletionStage<?> pollAsync(Queue<T> queue);

	static <T> Enqueuer<T> newEnqueuer(Poller<T> poller, Enqueuer.Conf config) {
		return new Enqueuer<T>(config) {
			@Override
			protected CompletionStage<?> pollAsync(Queue<T> queue) {
				return poller.pollAsync(queue);
			}
		};
	}

	static <T> Enqueuer<T> newEnqueuer(Poller<T> poller) {
		return newEnqueuer(poller, new Enqueuer.Conf());
	}

	static <T> Enqueuer<T> newEnqueuer(Poller<T> poller, Consumer<Enqueuer.Conf> c) {
		return newEnqueuer(poller, ActorishUtil.with(new Enqueuer.Conf(), c));
	}

	/**
	 * Creates Poller that polls by one.
	 */
	static <T> Poller<T> pollByOne(Function<T, CompletionStage<?>> receiverFn) {
		requireNonNull(receiverFn);
		return q -> {
			T item = q.poll();
			return item != null ? receiverFn.apply(item) : null;
		};
	}

	/**
	 * Creates Poller that polls by chunk.
	 */
	static <T> Poller<T> pollByChunk(int maxChunkSize, Function<List<T>, CompletionStage<?>> receiverFn) {
		if (maxChunkSize <= 0) throw new IllegalArgumentException();
		requireNonNull(receiverFn);
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
			return !chunk.isEmpty() ? receiverFn.apply(chunk) : null;
		};
	}
}
