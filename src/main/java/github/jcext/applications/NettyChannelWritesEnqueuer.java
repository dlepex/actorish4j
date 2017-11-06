package github.jcext.applications;


import github.jcext.Enqueuer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static github.jcext.JcExt.with;

/**
 * Experimental.<p>
 * NettyChannelWritesEnqueuer orders writes to Netty channel and flush them when it's appropriate to do so.
 * Note that there is no artificial delay before flushing.
 */
public final class NettyChannelWritesEnqueuer extends Enqueuer<ByteBuf> {
	private static final int defaultFlushLimit = 8192 * 4;

	public static class Conf extends Enqueuer.Conf {

		private int flushLimit = defaultFlushLimit;

		private void setFlushLimit(int flushLimit) {
			if (flushLimit <= 0) throw new IllegalArgumentException();
			this.flushLimit = flushLimit;
		}
	}

	private final Channel chan;
	private final int flushLimit;

	public NettyChannelWritesEnqueuer(Channel chan, Conf config) {
		super(with(config, c -> c.setAssociatedId(chan.id())));
		this.chan = chan;
		this.flushLimit = config.flushLimit;
	}

	public NettyChannelWritesEnqueuer(Channel chan) {
		this(chan, new Conf());
	}

	@Override
	protected CompletionStage<?> pollAsync(Queue<ByteBuf> q) {
		int bytesToFlush = 0;
		int flushLimit = this.flushLimit;
		CompletionStage<?> stage = CompletableFuture.completedFuture(null);
		for (ByteBuf buf = q.poll(); buf != null; ) {
			int bufSize = buf.readableBytes();
			if (bufSize > flushLimit) {
				throw new IllegalStateException("Buffer size too large: " + bufSize + " limit: " + flushLimit);
			}
			bytesToFlush += bufSize;
			if (bytesToFlush <= flushLimit) {
				stage = doWrite(stage, buf);
			} else {
				bytesToFlush = 0;
				stage = doFlush(stage);
				stage = doWrite(stage, buf);
			}
		}
		return doFlush(stage);
	}

	private CompletionStage<?> doFlush(CompletionStage<?> stage) {
		return stage.thenRun(chan::flush);
	}

	private CompletionStage<?> doWrite(CompletionStage<?> stage, ByteBuf b) {
		return stage.thenCompose(ignored -> nettyStage(chan.write(b)));
	}

	private static CompletionStage<?> nettyStage(ChannelFuture f) {
		CompletableFuture<?> r = new CompletableFuture();
		f.addListener(future -> r.complete(null));
		return r;
	}
}
