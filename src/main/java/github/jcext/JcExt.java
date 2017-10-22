package github.jcext;


import java.util.concurrent.CompletableFuture;

final class JcExt {

	static final CompletableFuture<Void> doneFuture = CompletableFuture.completedFuture(null);

	private JcExt() {
	}
}
