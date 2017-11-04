package github.jcext;

abstract class EnqueuerBasedEntity {
	/**
	 * User associated id. Can be anything, with good toString() method.
	 * @see github.jcext.Enqueuer.Conf#setId(Object)
	 */
	public Object id() {
		return underlyingEnq().id();
	}

	protected abstract Enqueuer<?> underlyingEnq();

	@Override
	public String toString() {
		Object id = id();
		if (id != null) {
			return getClass().getSimpleName() + "@" + id;
		}
		return super.toString();
	}
}
