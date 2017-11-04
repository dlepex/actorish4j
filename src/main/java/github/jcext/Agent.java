package github.jcext;


import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;

import static github.jcext.JcExt.with;
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
 * Be careful, all methods in this class may throw RejectedExecutionException, if queue overflows
 *
 * @param <S> state type. It's recommended for S to be immutable
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public final class Agent<S> extends EnqueuerBasedEntity {

	private final TaskEnqueuer enq;
	/**
	 * Shared mutable state.
	 * Non-volatile since all accesses to this field are done inside TaskEnqueuer tasks.
	 */
	private S state;

	public static TaskEnqueuer.Conf newConf() {
		return TaskEnqueuer.newConf();
	}

	public static <S> Agent<S> create(S initialState, TaskEnqueuer.Conf config) {
		return new Agent<>(initialState, TaskEnqueuer.create(config));
	}

	public static <S> Agent<S> create(S initialState) {
		return create(initialState, newConf());
	}

	public static <S> Agent<S> create(S initialState, Consumer<TaskEnqueuer.Conf> configInit) {
		return create(initialState, with(newConf(), configInit));
	}

	private Agent(S state, TaskEnqueuer enq) {
		this.enq = enq;
		this.state = state;
	}

	public CompletionStage<S> get() {
		return get(st -> st);
	}

	public <A> CompletionStage<A> get(Function<? super S, ? extends A> mapper) {
		return getAsync(st -> completedFuture(mapper.apply(st)));
	}

	public <A> CompletionStage<A> getAsync(Function<? super S, ? extends CompletionStage<A>> asyncMapper) {
		return enq.mustOfferCall(() -> asyncMapper.apply(state));
	}

	public void updateAsync(Function<? super S, ? extends CompletionStage<? extends S>> asyncModifierFn) {
		enq.mustOffer(() -> asyncModifierFn.apply(this.state).thenAccept(newState -> this.state = newState));
	}

	public void update(Function<? super S, ? extends S> modifierFn) {
		enq.mustOffer(() -> {
			this.state = modifierFn.apply(this.state);
			return null;
		});
	}

	public <A> CompletionStage<A> getAndUpdateAsync(
			Function<? super S, ? extends CompletionStage<StateValuePair<S, A>>> asyncModifierFn) {

		return enq.mustOfferCall(() -> asyncModifierFn.apply(this.state).thenApply(tuple -> {
			this.state = tuple.state;
			return tuple.value;
		}));
	}

	public <A> CompletionStage<A> getAndUpdate(Function<? super S, StateValuePair<S, A>> modifierFn) {
		return getAndUpdateAsync(st -> completedFuture(modifierFn.apply(st)));
	}

	@Override
	protected Enqueuer<?> underlyingEnq() {
		return enq.underlyingEnq();
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
