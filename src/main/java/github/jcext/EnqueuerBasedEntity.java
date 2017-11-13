package github.jcext;

import java.util.concurrent.Executor;

abstract class EnqueuerBasedEntity {
	/**
	 * User associated id, can be anything having good toString() method.
	 * @see Enqueuer.Conf#setAssociatedId(Object)
	 */
	public Object associatedId() {
		return underlyingEnq().associatedId();
	}

	protected abstract Enqueuer<?> underlyingEnq();

	@Override
	public String toString() {
		Object id = associatedId();
		if (id != null) {
			return getClass().getSimpleName() + "@" + id;
		}
		return super.toString();
	}
}
