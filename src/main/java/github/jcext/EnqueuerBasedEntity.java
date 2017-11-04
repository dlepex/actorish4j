package github.jcext;

public abstract class EnqueuerBasedEntity {

	/**
	 * Current queue size. For logging/monitoring usage only!
	 */
	public int queueSize() {
		return enq().queueSize();
	}

	/**
	 * User associated id. Can be anything, with good toString() method.
	 * @see github.jcext.Enqueuer.Conf#setId(Object)
	 */
	public Object id() {
		return enq().id();
	}

	abstract Enqueuer<?> enq();

	@Override
	public String toString() {
		Object id = id();
		if (id != null) {
			return getClass().getSimpleName() + "@" + id;
		}
		return super.toString();
	}
}
