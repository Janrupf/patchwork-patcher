package net.coderbot.patchwork.tasking;

/**
 * Class for simply submitting {@link Runnable}s
 */
public class RunnableTask extends Task {
	private final Runnable runnable;

	/**
	 * Creates a new RunnableTask with a specific Runnable
	 *
	 * @param runnable The Runnable to run
	 */
	public RunnableTask(Runnable runnable) {
		this.runnable = runnable;
	}

	@Override
	protected void run() {
		runnable.run();
	}
}
