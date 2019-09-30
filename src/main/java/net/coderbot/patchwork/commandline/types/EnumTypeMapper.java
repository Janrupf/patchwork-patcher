package net.coderbot.patchwork.commandline.types;

import net.coderbot.patchwork.commandline.CommandlineException;
import net.coderbot.patchwork.commandline.CommandlineParseException;

import java.lang.reflect.Field;

/**
 * Special type mapper for enums. Values will be made into lower case.
 *
 * @see BasicTypeMapper BasicTypeMapper for the base implementation
 */
public class EnumTypeMapper extends BasicTypeMapper<Enum> {
	private final Enum[] values;

	public EnumTypeMapper(Object target, Field f) throws CommandlineException {
		super(target, f);

		if(!f.getType().isEnum()) {
			throw new CommandlineException("Type " + f.getType().getName() + " is not an enum");
		}

		try {
			Field enumValues = f.getType().getDeclaredField("$VALUES");
			enumValues.setAccessible(true);
			this.values = (Enum[]) enumValues.get(null);
		} catch(IllegalAccessException | NoSuchFieldException e) {
			throw new CommandlineException("Failed to retrieve possible enum values for " + f.getType().getName());
		}
	}

	/**
	 * Sets the underlying field to the value specified
	 *
	 * @param value The value to set the field to
	 * @throws CommandlineParseException If an invalid value is supplied
	 * @throws CommandlineException If an error occurs setting the underlying field
	 */
	@Override
	public void apply(String value) throws CommandlineException {
		for(Enum possible : values) {
			if(possible.name().toLowerCase().equals(value)) {
				set(possible);
				return;
			}
		}

		throw new CommandlineParseException(value + " is not a possible value");
	}

	@Override
	public boolean acceptsValue() {
		return true;
	}
}
