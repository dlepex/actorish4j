package github.jcext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

/**
 * Experimental implementation of Erlang gen_statem (gen_fsm) in a "state functions mode".<p>
 * <a href="http://erlang.org/doc/design_principles/statem.html">gen_statem Behavior</a> <p>
 * <p>
 * "State functions mode" means that the state is identified by {@link StateFunc}, which handles events in that state.
 * <p>
 * This class contains lots of transition method forms: goTo(...) including forms with timeouts and async transitions. <p>
 * <p>
 * There're two predefined states that can be used with goTo: {@link #sameState()} and {@link #finalState()}
 * <p>
 * In its final state StateMachine will just keep logging (debug level) events forever,
 * this can be customized {@link Conf#setFinalStateEventHandler(Consumer)} <p>
 * <p>
 * Be careful, if StateFunc throws uncaught exception then StateMachine will go to {@link #finalState()} automatically. <p>
 * Clients can be notified when the final state was reached {@link #finalStateReached()} and determine the cause of it <p>
 *
 * @param <E> event type
 */
public abstract class StateMachine<E> extends EnqueuerBasedEntity {

	private static final Logger log = LoggerFactory.getLogger(StateMachine.class);

	public static class Conf extends Enqueuer.Conf {
		private Timer timer = Timer.defaultInstance();
		private Consumer<Object> eventConsumer;

		public void setTimer(Timer timer) {
			this.timer = timer;
		}


		/**
		 * Use it only for logging or monitoring, if you're not happy with default behaviour.
		 *
		 * @param handler will be called for each event when final state reached.
		 */
		public void setFinalStateEventHandler(Consumer<Object> handler) {
			this.eventConsumer = requireNonNull(handler);
		}
	}

	/**
	 * This class exists to enforce the rule that the last statement in StateFunc must be goTo(...)
	 */
	@SuppressWarnings("all")
	protected static final class NextState {
		private NextState() {
		}
	}

	private static final StateFunc FinalState = ignored -> null;
	private final static NextState NextStateVal = new NextState();

	@FunctionalInterface
	protected interface StateFunc<E> {
		NextState apply(E event);
	}

	protected final Timer timer;
	private final Enqueuer<E> enq;
	private final CompletableFuture<?> finalStateReached = new CompletableFuture<>();
	private final Consumer<Object> endStateEventConsumer;

	private StateFunc<E> state;
	private CompletionStage<StateFunc<E>> nextAsync;


	protected StateMachine(Conf c) {
		this.enq = Poller.newEnqueuer(this::pollAsync, c);
		this.timer = c.timer;
		this.state = initialState();
		this.endStateEventConsumer = c.eventConsumer;
	}

	protected abstract StateFunc<E> initialState();

	/**
	 * Descendant classes may override this method to be public, or define their own business specific methods which call send().
	 */
	protected void send(E event) throws RejectedExecutionException {
		if (!enq.offer(event)) {
			throw new RejectedExecutionException(toString());
		}
	}

	@Override
	protected final Enqueuer<?> underlyingEnq() {
		return enq;
	}

	@SuppressWarnings("unchecked")
	private CompletionStage<?> pollAsync(Queue<E> queue) {
		StateFunc<E> state = this.state;
		if (state == FinalState) {
			handleFinalStateEvent(queue.poll());
			return null;
		}

		try {
			NextState dummy = state.apply(queue.poll());
			requireNonNull(dummy, "StateFunc must end with \"return goTo(...) statement\"");
		} catch (Exception ex) {
			this.state = FinalState;
			this.finalStateReached.completeExceptionally(ex);
			return null;
		}

		CompletionStage<StateFunc<E>> nextAsync = this.nextAsync;
		this.nextAsync = null;
		return nextAsync.whenComplete(nextAsyncCompletionHandler);
	}

	@SuppressWarnings("unchecked")
	private final BiConsumer<StateFunc<E>, Throwable> nextAsyncCompletionHandler = (nextState, ex) -> {
		if (nextState != null) {
			this.state = nextState;
			if (nextState == FinalState) {
				this.finalStateReached.complete(null);
			}
		} else {
			this.state = FinalState;
			this.finalStateReached.completeExceptionally(ex != null ? ex : new NullPointerException("nextState can't be null"));
		}
	};

	protected final NextState goTo(CompletionStage<StateFunc<E>> nextAsync) {
		this.nextAsync = requireNonNull(nextAsync);
		return NextStateVal;
	}


	protected final NextState goTo(CompletionStage<StateFunc<E>> nextAsync, long delay, TimeUnit unit) {
		requireNonNull(nextAsync);
		return goTo(nextAsync.thenCompose(val -> timer.delayValue(val, delay, unit)));
	}

	protected final NextState goTo(StateFunc<E> next, long delay, TimeUnit unit) {
		requireNonNull(next);
		return goTo(completedFuture(next), delay, unit);
	}

	protected final NextState goTo(StateFunc<E> next, int delayMillis) {
		requireNonNull(next);
		return goTo(next, delayMillis, TimeUnit.MILLISECONDS);
	}

	protected final NextState goTo(StateFunc<E> next) {
		requireNonNull(next);
		return goTo(completedFuture(next));
	}

	protected final StateFunc<E> sameState() {
		return state;
	}

	@SuppressWarnings("unchecked")
	protected final StateFunc<E> finalState() {
		return FinalState;
	}

	public CompletionStage<?> finalStateReached() {
		return finalStateReached;
	}

	private void handleFinalStateEvent(Object event) {
		if (endStateEventConsumer == null) {
			if (log.isDebugEnabled()) {
				log.debug("{} finished, event: {}", toString(), event);
			}
		} else {
			endStateEventConsumer.accept(event);
		}
	}

}
