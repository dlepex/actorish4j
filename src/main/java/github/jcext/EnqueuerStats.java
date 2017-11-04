package github.jcext;

/**
 * API for reporting/monitoring usage.
 * Stats usage only, never use it for the logic of your App.
 */
abstract class EnqueuerStats {

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
