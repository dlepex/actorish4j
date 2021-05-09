package io.github.actorish4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

/**
 * Event-Driven State Machine implementation inspired by Erlang gen_statem (gen_fsm) behaviour in a "state functions mode".<p>
 * <a href="http://erlang.org/doc/design_principles/statem.html">gen_statem Behavior</a> <p>
 * <p>
 * "State functions mode" means that each state has  {@link StateFunc}, which handles events.
 * <p>
 * This class provides various transition methods forms : goTo(...) including forms with timeouts and async transitions. <p>
 * <p>
 * There're two predefined states that can be used with goTo: {@link #sameState()} and {@link #finalState()}
 * <p>
 *   To create a state machine, the user must inherit from this class, implement {@link #initialState()} method
 *   and override {@link #send(Object)} to be public or better provide your own specific sending methods which call send() internally.
 * <p>
 * In its final state StateMachine will just keep logging (debug level) events forever,
 * this can be customized {@link Conf#setFinalStateHandler(EventConsumer)} <p>
 * <p>
 * Be careful, if StateFunc throws an uncaught exception then StateMachine will go to {@link #finalState()} automatically,
 * this is also customizable, see: {@link #recover(Throwable)} <p>
 * Clients can be notified when the final state was reached {@link #finalStateReached()} and determine the cause of it <p>
 *
 * @param <E> event type
 */
public abstract class StateMachine<E> extends EnqueuerBasedEntity {

	private static final Logger log = LoggerFactory.getLogger(StateMachine.class);

	/**
	 * Configuration object
	 */
	public static class Conf extends Enqueuer.Conf {
		private Timer timer = Timer.defaultInstance();
		private EventConsumer finStateEvHandler;

		public void setTimer(Timer timer) {
			this.timer = timer;
		}


		/**
		 * Use it only for logging or monitoring, if you're not happy with the default behaviour.
		 *
		 * @param handler will be called for each event when final state reached.
		 */
		public void setFinalStateHandler(EventConsumer handler) {
			this.finStateEvHandler = requireNonNull(handler);
		}
	}

	@FunctionalInterface
	public interface EventConsumer {
		/**
		 * StateMachine is passed here only for printing or monitoring (toString()/associatedId() methods)
		 */
		void accept(StateMachine<?> m, Object event);
	}

	/**
	 * This class only exists to enforce the rule that the last statement in a StateFunc must be {@code return goTo(...)}.
	 * It guarantees that a StateFunc is always *explicitly* ended with transition to some new state.
	 */
	@SuppressWarnings("all")
	protected static final class NextState {
		private static final NextState VALUE = new NextState();
		private NextState() {
		}
	}
	private static final StateFunc FIN_STATE = ignored -> null;


	/**
	 * Event handler for some state.
	 * The state is identified only by its event handler, no other identities/names exist.
	 * It must be ended with  {@code return goTo(...)} i.e. a jump to some state.
	 * @param <E>
	 */
	@FunctionalInterface
	protected interface StateFunc<E> {
		NextState apply(E event);
	}

	protected final Timer timer;
	private final Enqueuer<E> enq;
	private final CompletableFuture<?> finalStateReached = new CompletableFuture<>();
	private final EventConsumer finStateEvConsumer;

	private StateFunc<E> state;
	private CompletionStage<StateFunc<E>> nextAsync;


	protected StateMachine(Conf c) {
		this.enq = Poller.newEnqueuer(this::pollAsync, c);
		this.timer = c.timer;
		this.finStateEvConsumer = c.finStateEvHandler != null ? c.finStateEvHandler : StateMachine::handleFinalStateDefault;
	}

	/**
	 * Descendants must implement this "getter". It must be side-effects free (as normally expected from getters)
	 * @return
	 */
	protected abstract StateFunc<E> initialState();

	/**
	 * Descendants may override this behaviour, by default StateMachine can't recover and so it goes to finalState()
	 * @param ex exception which was thrown in StateFunc directly or indirectly, that is in async state transitions (nextAsync parameters of goTo)
	 */
	protected StateFunc<E> recover(Throwable ex) {
		return finalState();
	}
	/**
	 * Descendants may override this method to be public, or better define their own specific methods which call send() internally.
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
		StateFunc<E> stOrNull = this.state;
		if (stOrNull == FIN_STATE) {
			finStateEvConsumer.accept(this, queue.poll());
			return null;
		}

		try {
			StateFunc<E> st = stOrNull != null ? stOrNull : initialState();
			NextState dummy = st.apply(queue.poll());
			requireNonNull(dummy, "StateFunc must end with \"return goTo(...) statement\"");
		} catch (Throwable ex) {
			tryRecover(ex);
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
			if (nextState == FIN_STATE) {
				finalStateReached.complete(null);
			}
		} else {
			if (ex != null) {
				tryRecover(ex);
			} else {
				this.state = FIN_STATE;
				log.error(toString() + " BUG: nextState can't be null");
				finalStateReached.completeExceptionally(new AssertionError("StateMachine impl. is broken: nextState can't be null"));
			}

		}
	};

	/**
	 * Basic async transition: all other transitions are expressed in terms of this method.
	 */
	protected final NextState goTo(CompletionStage<StateFunc<E>> nextAsync) {
		this.nextAsync = requireNonNull(nextAsync);
		return NextState.VALUE;
	}


	/**
	 * Async transition, with delay after async computation.
	 */
	protected final NextState goTo(CompletionStage<StateFunc<E>> nextAsync, long delay, TimeUnit unit) {
		requireNonNull(nextAsync);
		return goTo(nextAsync.thenCompose(val -> timer.delayValue(val, delay, unit)));
	}

	/**
	 * Async transition with delay after async computation.
	 */
	protected final NextState goTo(CompletionStage<StateFunc<E>> nextAsync, int delayMillis) {
		requireNonNull(nextAsync);
		return goTo(nextAsync, delayMillis, TimeUnit.MILLISECONDS);
	}

	/**
	 * Sync transition with delay
	 */
	protected final NextState goTo(StateFunc<E> next, long delay, TimeUnit unit) {
		requireNonNull(next);
		return goTo(completedFuture(next), delay, unit);
	}

	/**
	 * Sync transition with delay
	 */
	protected final NextState goTo(StateFunc<E> next, int delayMillis) {
		requireNonNull(next);
		return goTo(next, delayMillis, TimeUnit.MILLISECONDS);
	}

	/**
	 * Sync transition
	 */
	protected final NextState goTo(StateFunc<E> next) {
		requireNonNull(next);
		return goTo(completedFuture(next));
	}

	protected final StateFunc<E> sameState() {
		return state;
	}

	@SuppressWarnings("unchecked")
	protected final StateFunc<E> finalState() {
		return FIN_STATE;
	}

	/**
	 * Use async-methods of resultant CompletionStage (with custom executor) if you need lengthy/blocking processing of final-state-reached event!
	 */
	public final CompletionStage<?> finalStateReached() {
		return finalStateReached;
	}

	private static void handleFinalStateDefault(StateMachine<?> m, Object event) {
		if (log.isDebugEnabled()) {
			log.debug("{} finished, event: {}", m.toString(), event);
		}
	}

	private void tryRecover(Throwable ex) {
		StateFunc<E> recState;
		try {
			recState = recover(ex);
		} catch (Throwable e) {
			log.error(toString() + ": BUG: recover() must never throw!", e);
			recState = finalState();
		}
		this.state = recState;
		if (recState == FIN_STATE) {
			finalStateReached.completeExceptionally(ex);
		}
	}

}
