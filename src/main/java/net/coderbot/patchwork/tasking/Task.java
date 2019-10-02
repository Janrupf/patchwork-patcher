package net.coderbot.patchwork.tasking;

import net.coderbot.patchwork.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Base class for all kinds of different tasks
 */
public abstract class Task {
	private final Object lockObject;
	private final List<Task> dependencies;
	private final List<BiConsumer<Task, TaskScheduler>> completionCallbacks;

	private TaskState state;
	private TaskScheduler scheduler;

	private FailReason failReason;
	private Throwable error;
	private String errorMessage;

	protected Task() {
		lockObject = new Object();
		dependencies = new ArrayList<>();
		completionCallbacks = new ArrayList<>();
		state = TaskState.UNINITIALIZED;
	}

	/**
	 * Adds a dependencies to this task.
	 *
	 * @param tasks The tasks to add as dependencies
	 * @throws IllegalArgumentException If no dependency is specified
	 * @throws IllegalStateException If the task is not in a waiting state
	 */
	public void after(Task ...tasks) {
		if(tasks.length < 1) {
			throw new IllegalArgumentException("Must specify at least on dependency, use now() for running" +
					"the task without dependencies");
		}

		for(Task task : tasks) {
			synchronized(task.lockObject) {
				switch(task.state) {
					case FAILED:
						changeState(TaskState.FAILED, false);
						return;
					case SUCCEEDED:
						continue;
					default:
						dependencies.add(task);
						task.completionCallbacks.add(this::checkCompletion);
				}
			}
		}

		if(dependencies.isEmpty()) {
			now(tasks[0].scheduler);
		} else {
			changeState(TaskState.WAITING, true);
		}
	}

	/**
	 * Immediately schedules this task
	 *
	 * @param scheduler The scheduler to schedule the task to
	 */
	public void now(TaskScheduler scheduler) {
		changeState(TaskState.WAITING, true);
		scheduler.schedule(this);
	}

	/**
	 * Determines why this task failed.
	 *
	 * @return The reason this task failed or <code>null</code>, if it didn't fail
	 */
	public final FailReason getFailReason() {
		return failReason;
	}

	/**
	 * Determines the throwable causing this task to fail.
	 *
	 * @return The throwable causing this task to fail or <code>null</code> if this task didn't fail with a Throwable
	 */
	public final Throwable getError() {
		return error;
	}

	/**
	 * Determines the error message this task failed with.
	 *
	 * @return The error message this task failed with or <code>null</code> if this task didn't fail.
	 */
	public final String getErrorMessage() {
		if(errorMessage != null) {
			return errorMessage;
		} else if(error != null) {
			return error.getMessage() == null ? "Unknown" : error.getMessage();
		} else {
			return null;
		}
	}

	/**
	 * This method is called by the scheduler to run the task itself.
	 *
	 * @param scheduler The scheduler running this task
	 */
	void fullRun(TaskScheduler scheduler) {
		this.scheduler = scheduler;
		try {
			changeState(TaskState.RUNNING, true);
			synchronized(lockObject) {
				run();
			}
			changeState(TaskState.SUCCEEDED, true);
		} catch(Throwable t) {
			// Catch everything
			fail(t);
		} finally {
			synchronized(lockObject) {
				completionCallbacks.forEach((callback) -> callback.accept(this, scheduler));
			};
		}
	}

	/**
	 * Sets the fail state and the error to a specific throwable. If the fail state is already set, calling
	 * this method has no effect.
	 *
	 * @param t The throwable to set as the error reason
	 * @throws NullPointerException If t is null
	 */
	protected final void fail(Throwable t) {
		Objects.requireNonNull(t, "t cannot be null");

		if(failReason == null) {
			this.error = t;
			failReason = FailReason.ERROR;
			changeState(TaskState.FAILED, false);
		}
	}

	/**
	 * Changes the state of this task to a new one.
	 *
	 * @param newState The target state
	 * @param requireImmediately If the new state needs to immediately follow the current state
	 * @throws IllegalStateException If the new state does not (immediately if requireImmediately) follow the
	 * 								 current state
	 */
	public final void changeState(TaskState newState, boolean requireImmediately) {
		if(requireImmediately && !newState.followsImmediately(state)) {
			throw new IllegalStateException(newState.name() + " does not immediately follow state " + state.name());
		} else if(!newState.follows(state)) {
			throw new IllegalStateException(newState.name() + " does not follow state " + state.name());
		}

		synchronized(lockObject) {
			Logger.getInstance().trace("Task state about to change for task %s from %s to %s",
					this, state.name(), newState.name());
			state = newState;
		}
	}

	/**
	 * Sets the fail state and the error to a specific message. If the fail state is already set, calling
	 * this method has no effect.
	 *
	 * @param message The message to set as the error reason
	 * @throws NullPointerException If message is null
	 */
	protected final void fail(String message) {
		Objects.requireNonNull(message, "Message cannot be null");

		if(failReason == null) {
			this.errorMessage = message;
			failReason = FailReason.ERROR;
			changeState(TaskState.FAILED, false);
		}
	}

	private void checkCompletion(Task dependency, TaskScheduler scheduler) {
		if(this.state != TaskState.WAITING) {
			return;
		} else if(dependency.state != TaskState.SUCCEEDED) {
			this.failReason = FailReason.DEPENDENCY_FAILED;
			changeState(TaskState.FAILED, false);
			return;
		}

		synchronized(lockObject) {
			dependencies.remove(dependency);

			if(dependencies.isEmpty()) {
				scheduler.schedule(this);
			}
		}
	}

	protected abstract void run();
}
