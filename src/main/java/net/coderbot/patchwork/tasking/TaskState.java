package net.coderbot.patchwork.tasking;

/**
 * Represents the state a task can have
 */
public enum TaskState {
	/**
	 * The task has not been initialized yet
	 */
	UNINITIALIZED(0),

	/**
	 * The task is waiting to be scheduled
	 */
	WAITING(1),

	/**
	 * The task has been scheduled and is awaiting run
	 */
	SCHEDULED(2),

	/**
	 * The task is currently running
	 */
	RUNNING(3),

	/**
	 * The task succeeded
	 */
	SUCCEEDED(4),

	/**
	 * The task failed
	 *
	 * @see FailReason
	 */
	FAILED(4);

	private final int order;

	TaskState(int order) {
		this.order = order;
	}

	/**
	 * Checks if this state immediately follows another state.
	 *
	 * @param previous The state to check if this one immediately follows it
	 * @return <code>true</code> if this state follows, <code>false</code> otherwise
	 */
	public boolean followsImmediately(TaskState previous) {
		return order - 1 == previous.order;
	}

	/**
	 * Check if this state follows another state.
	 *
	 * @param other The state to check if this one follows it.
	 * @return <code>true</code> if this state follows, <code>false</code> otherwise
	 */
	public boolean follows(TaskState other) {
		return order > other.order;
	}
}
