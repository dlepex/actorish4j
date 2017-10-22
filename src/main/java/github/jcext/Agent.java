package github.jcext;


import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import static java.util.concurrent.CompletableFuture.completedFuture;

/**
 * Agents provide access to shared mutable state in async fashion.
 * When to use Agents: when you need lock-like behaviour for your async computations.
 * It's impossible to get the state of agent synchronously, only thru the Future.
 * <p>
 * This implementation is inspired by Elixir Agents: https://hexdocs.pm/elixir/Agent.html
 * Clojure Agents are different (more limited), aside from the fact that they can participate in STM
 * <p>
 *
 * @param <S> state type. It's recommended for S to be immutable
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public final class Agent<S> {

	private final TaskEnqueuer q;
	/**
	 * Shared mutable state.
	 * Non-volatile since all accesses to this field are done inside TaskEnqueuer tasks.
	 */
	private S state;


	public static <S> Agent<S> create(Enqueuer.Conf c, S initialState) {
		return new Agent<>(TaskEnqueuer.create(c), initialState);
	}

	private Agent(TaskEnqueuer q, S state) {
		this.q = q;
		this.state = state;
	}

	public CompletionStage<S> get() {
		return get(st -> st);
	}

	public <A> CompletionStage<A> get(Function<? super S, ? extends A> mapper) {
		return getAsync(st -> completedFuture(mapper.apply(st)));
	}

	public <A> CompletionStage<A> getAsync(Function<? super S, ? extends CompletionStage<A>> asyncMapper) {
		return q.mustOfferCall(() -> asyncMapper.apply(state));
	}

	public void updateAsync(Function<? super S, ? extends CompletionStage<? extends S>> asyncModifierFn) {
		q.mustOffer(() -> asyncModifierFn.apply(this.state).thenAccept(newState -> this.state = newState));
	}

	public void update(Function<? super S, ? extends S> modifierFn) {
		q.mustOffer(() -> {
			this.state = modifierFn.apply(this.state);
			return null;
		});
	}

	public <A> CompletionStage<A> getAndUpdateAsync(
			Function<? super S, ? extends CompletionStage<StateValuePair<S, A>>> asyncModifierFn) {

		return q.mustOfferCall(() -> asyncModifierFn.apply(this.state).thenApply(tuple -> {
			this.state = tuple.state;
			return tuple.value;
		}));
	}

	public <A> CompletionStage<A> getAndUpdate(Function<? super S, StateValuePair<S, A>> modifierFn) {
		return getAndUpdateAsync(st -> completedFuture(modifierFn.apply(st)));
	}


	public final static class StateValuePair<S, V> {
		public final S state;
		public final V value;

		public StateValuePair(S state, V value) {
			this.state = state;
			this.value = value;
		}
	}

}
