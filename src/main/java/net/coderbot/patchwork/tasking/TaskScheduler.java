package net.coderbot.patchwork.tasking;

import net.coderbot.patchwork.logging.LogLevel;
import net.coderbot.patchwork.logging.Logger;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates task's and makes sure their dependencies have run
 */
public class TaskScheduler {
	private final ThreadGroup taskThreadGroup;
	private final TaskThread[] taskThreads;
	private final Logger logger;
	private final Queue<Task> waitingTasks;
	private final AtomicBoolean shutdown;

	/**
	 * Creates a new task scheduler with a specific amount of threads
	 *
	 * @param threadCount The amount of threads to spawn later on
	 */
	public TaskScheduler(int threadCount) {
		taskThreadGroup = new SchedulerThreadGroup();
		taskThreads = new TaskThread[threadCount];
		logger = Logger.getInstance();
		waitingTasks = new LinkedList<>();
		shutdown = new AtomicBoolean(false);
	}

	/**
	 * Schedules a task which should run as soon as a thread is available.
	 * Tasks passed in here are assumed to have their dependencies fulfilled, since
	 * once a task completes it will signal its dependencies which will then schedule themselves.
	 *
	 * @param toRun The task which should be scheduled
	 */
	void schedule(Task toRun) {
		synchronized(waitingTasks) {
			waitingTasks.add(toRun);
			toRun.changeState(TaskState.SCHEDULED, true);
			waitingTasks.notify();
		}
	}

	/**
	 * Starts the scheduler
	 */
	public void start() {
		logger.debug("Starting task scheduler");
		for(int i = 0; i < taskThreads.length; i++) {
			logger.trace("Starting task thread %d", i);
			taskThreads[i] = new TaskThread(i);
			taskThreads[i].start();
		}
	}

	/**
	 * Signals all threads to shutdown
	 */
	public void shutdown() {
		logger.debug("Shutting down task scheduler");
		shutdown.set(true);
		for(TaskThread thread : taskThreads) {
			logger.trace("Interrupting task thread %s", thread.getName());
			if(!thread.isInterrupted()) {
				thread.interrupt();
			}
		}
	}

	private class SchedulerThreadGroup extends ThreadGroup {
		private SchedulerThreadGroup() {
			super("TaskSchedulerGroup");
		}

		@Override
		public void uncaughtException(Thread t, Throwable e) {
			if(!(t instanceof TaskThread)) {
				logger.error("A thread called %s spawned by a task thread crashed!", t.getName());
				logger.thrown(LogLevel.ERROR, e);
				return;
			} else if(e instanceof InterruptedException) {
				logger.trace("Task thread %s is terminating with a InterruptedException, allowing to die...");
				logger.thrown(LogLevel.ERROR, e);
				return;
			}

			logger.error("Task thread %s threw uncaught exception!", t.getName());
			logger.thrown(LogLevel.ERROR, e);

			// Try to recover from the exception
			TaskThread taskThread = (TaskThread) t;
			if(taskThreads[taskThread.index] != taskThread) {
				// This should NEVER happen, however, since we are working in a heavily threaded
				// environment we check first
				throw new IllegalStateException("Task scheduler corrupted");
			}

			logger.trace("Replacing task thread %d", taskThread.index);
			taskThreads[taskThread.index] = new TaskThread(taskThread.index);
			taskThreads[taskThread.index].start();
		}
	}

	private class TaskThread extends Thread {
		private final int index;

		private TaskThread(int index) {
			super(taskThreadGroup, "TaskSchedulerThread" + index);
			this.index = index;
		}

		@Override
		public void run() {
			while(!shutdown.get()) {
				Task task;
				synchronized(waitingTasks) {
					if(waitingTasks.size() < 1) {
						try {
							logger.trace("Task thread %d waiting for available task", index);
							waitingTasks.wait();
						} catch(InterruptedException ignored) {
						}
						continue;
					} else {
						logger.trace("Task thread %d accepting next task", index);
						task = waitingTasks.remove();
					}
				}
				task.fullRun(TaskScheduler.this);
			}
		}
	}
}
