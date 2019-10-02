package net.coderbot.patchwork.tasking;

/**
 * Possible reasons a task failed
 */
public enum FailReason {
	/**
	 * This task was terminated by a throwable or a user supplied message
	 */
	ERROR,

	/**
	 * A dependency of this task failed
	 */
	DEPENDENCY_FAILED
}
