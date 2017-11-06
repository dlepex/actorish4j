package github.jcext;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * This interface provides alternative (lambda-friendly) ways to create {@link Enqueuer} instances.
 *
 * @param <T>
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
		return newEnqueuer(poller, JcExt.with(new Enqueuer.Conf(), c));
	}

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
