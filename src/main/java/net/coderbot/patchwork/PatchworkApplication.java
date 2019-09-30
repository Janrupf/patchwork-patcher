package net.coderbot.patchwork;

import com.electronwill.nightconfig.core.Config;
import net.coderbot.patchwork.commandline.CommandlineException;
import net.coderbot.patchwork.commandline.CommandlineParser;
import net.coderbot.patchwork.commandline.Flag;
import net.coderbot.patchwork.logging.LogLevel;
import net.coderbot.patchwork.logging.Logger;

import java.io.File;
import java.net.URL;

import com.electronwill.nightconfig.core.conversion.ObjectBinder;
import com.electronwill.nightconfig.core.file.FileConfig;

public class PatchworkApplication {
	private static class Commandline {
		@Flag(names = { "h", "help" }, description = "Displays this message")
		boolean help;

		@Flag(names = "no-color", description = "Disable colors on terminal output")
		boolean disableColors;
	}

	public static void main(String[] args) {
		// First of all, check a file called `patchwork-commandline.toml` so
		// supplying the commandline in development is easy
		File commandlineToml = new File("patchwork-commandline.toml");

		Commandline commandline = new Commandline();
		if(commandlineToml.exists()) {
			System.out.println("Applying commandline from patchwork-commandline.toml");
			FileConfig config = FileConfig.of(commandlineToml);
			config.load();
			ObjectBinder binder = new ObjectBinder();

			// Binding the object and applying the values from the read config will set the fields
			Config boundConfig = binder.bind(commandline);
			config.valueMap().forEach(boundConfig::set);
		} else {
			CommandlineParser<Commandline> parser = new CommandlineParser<>(commandline, args);
			try {
				parser.parse();
			} catch(CommandlineException e) {
				System.err.println("BUG: Internal error reading commandline!");
				e.printStackTrace();
				System.exit(1);
			}

			if(!parser.parseSucceeded() || commandline.help) {
				System.out.println(parser.generateHelpMessage(getExecutableName(),
						"Patchwork Patcher v0.1.0",
						"Patchwork Patcher is a set of tools for transforming and patchingForge mod jars\n"
								+ "into jars that are directly loadable by Fabric Loader.",
						"This program is still in an unstable alpha state",
						!commandline.disableColors));

				System.exit(parser.parseSucceeded() ? 0 : 1);
			}
		}

		Logger logger = Logger.getInstance();

	}

	private static String getExecutableName() {
		try {
			URL location =
					PatchworkApplication.class.getProtectionDomain().getCodeSource().getLocation();
			if(location.getProtocol().equals("file")) {
				return location.getPath();
			}
		} catch(Exception e) {
			Logger.getInstance().debug("Failed to get executable name");
			Logger.getInstance().thrown(LogLevel.DEBUG, e);
		}

		return "/path/to/patchwork.jar";
	}
}
