package github.jcext.applications;



import github.jcext.Enqueuer;
import github.jcext.JcExt;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;


//TODO work in progress: a toy class to demonstrate that Erlang Actors (the "receive" statement) can be easily implemented on top of the Enqueuer.
public abstract class ErlangActor {


	private final Enqueuer<Object> enq;
	private final Timer timer;

	protected ErlangActor( Timer timer) {
		enq = new Enqueuer<Object>(new Enqueuer.Conf()) {
			@Override
			protected CompletionStage<?> pollAsync(Queue queue) {
				return actorLogic(queue);
			}
		};
		this.timer = Objects.requireNonNull(timer);
	}

	public enum SpecialMsg {
		Timeout
	}


	protected abstract Receive start();


	protected final Receive receive(ReceiverFunc f) {
		return null;
	}

	protected final Receive receive(ReceiverFunc f, int timeout) {
		return null;
	}



	@FunctionalInterface
	protected interface ReceiverFunc {
		CompletionStage<Receive> receive(Object item);

		/**
		 * Erlang receive after-clause.
		 */
		default CompletionStage<Receive> after() {
			return receive(SpecialMsg.Timeout);
		}
	}

	protected final class Receive {
		ReceiverFunc func;
		int timeout;
	}



	private ArrayList<Object> stash = new ArrayList<>();
	private Receive recv = null;
	private long token = 0;


	private static class InternalMsg {
		Receive recv;
		long timeoutToken;

		private InternalMsg(Receive recv, long timeoutRecvNum) {
			this.recv = recv;
			this.timeoutToken = timeoutRecvNum;
		}
	}

	private CompletionStage<?> actorLogic(Queue<Object> q) {

		Object m = q.poll();

		if (m instanceof InternalMsg) {
			InternalMsg msg = (InternalMsg) m;
			Receive r = msg.recv;
			long timeoutToken = msg.timeoutToken;
			if (r != null) {
				if (r.timeout != 0) {
					this.token ++;
					long token = this.token;
					timer.timeout(() -> sendInternal(token), r.timeout, TimeUnit.MILLISECONDS);
				}
			} else if (timeoutToken != 0 && timeoutToken == this.token) {
				Receive recv = this.recv;
				assert recv != null;
				this.recv = null;
				recv.func.after();
			}

		} else {
			Receive recv = this.recv;
			if (recv != null) {

			} else {
				stash.add(m);
			}

		}

		return null;

	}


	private void sendInternal(Receive r) {

	}

	private void sendInternal(long timeoutToken) {

	}






}
