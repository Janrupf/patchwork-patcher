# Patchwork: Patcher

Patchwork Patcher is a set of tools for transforming and patching Forge mod jars into jars that are directly loadable by Fabric Loader. It does the following things currently:

* Remaps Minecraft from official (proguard's obfuscated names) to srg (MCP's runtime mappings)

* Remaps the mod jar from srg to official using Tiny Remapper with the srg minecraft jar on the classpath

* Remaps the mod jar from official to intermediary using Tiny Remapper with the official minecraft jar on the classpath

* Converts the Forge mods.toml manifest to a fabric.mod.json file

* Converts @OnlyIn annotations to @Environment for Fabric

* Strips @ObjectHolder annotations, removes the field's final modifiers, and creates Consumers that set the fields

* Generates a class implementing ForgeInitializer that sets up these consumers with Patchwork Runtime's ObjectHolderRegistry

* Strips @Mod.EventBusSubscriber and @SubscribeEvent annotations but does not currently save them (Events are planned)

	* Plan: Use a similar system to that used for Object Holders
	
	
## Note on Patchwork Runtime

Patchwork Patcher generates jars that require a Fabric mod acting as a compatibility layer to run, currently referred to as Patchwork Runtime. Patchwork Runtime currently doesn't actually work at all as intended (it directly calls the functions from mod jars) so it's not yet published, but as soon as it is capable of executing loading and events in a general manner it will be published.
