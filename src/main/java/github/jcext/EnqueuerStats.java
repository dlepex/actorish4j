package github.jcext;

abstract class EnqueuerStats {

	/**
	 * Stats usage only, never use it for the logic of your App.
	 */
	public int queueSize() {
		return enq().queueSize();
	}

	/**
	 * user associated id.
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
