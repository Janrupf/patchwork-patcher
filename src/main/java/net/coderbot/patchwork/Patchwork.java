package net.coderbot.patchwork;

import net.coderbot.patchwork.access.AccessTransformation;
import net.coderbot.patchwork.access.AccessTransformations;
import net.coderbot.patchwork.access.AccessTransformer;
import net.coderbot.patchwork.annotation.AnnotationProcessor;
import net.coderbot.patchwork.event.EventBusSubscriber;
import net.coderbot.patchwork.event.EventHandlerScanner;
import net.coderbot.patchwork.event.SubscribeEvent;
import net.coderbot.patchwork.event.generator.StaticEventRegistrarGenerator;
import net.coderbot.patchwork.event.generator.SubscribeEventGenerator;
import net.coderbot.patchwork.manifest.converter.ModManifestConverter;
import net.coderbot.patchwork.manifest.forge.ModManifest;
import net.coderbot.patchwork.mapping.*;
import net.coderbot.patchwork.objectholder.*;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.electronwill.nightconfig.core.file.FileConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.mappings.Mappings;
import net.fabricmc.mappings.MappingsProvider;
import net.fabricmc.tinyremapper.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

public class Patchwork {
	public static void main(String[] args) throws Exception {
		Mappings intermediary = MappingsProvider.readTinyMappings(
				new FileInputStream(new File("data/mappings/intermediary-1.14.4.tiny")));
		List<TsrgClass<RawMapping>> classes = Tsrg.readMappings(
				new FileInputStream(new File("data/mappings/voldemap-1.14.4.tsrg")));

		IMappingProvider intermediaryMappings = TinyUtils.createTinyMappingProvider(
				Paths.get("data/mappings/intermediary-1.14.4.tiny"), "official", "intermediary");

		TsrgMappings mappings = new TsrgMappings(classes, intermediary, "official");
		String tiny = mappings.writeTiny("srg");

		Files.write(Paths.get("data/mappings/voldemap-1.14.4.tiny"),
				tiny.getBytes(StandardCharsets.UTF_8));

		Files.createDirectories(Paths.get("input"));
		Files.createDirectories(Paths.get("temp"));
		Files.createDirectories(Paths.get("output"));

		// This takes a long time, so we skip it.
		//
		// System.out.println("Remapping Minecraft (official -> srg)");
		// remap(mappings, Paths.get("data/1.14.4+official.jar"), Paths.get("data/1.14.4+srg.jar"));

		Files.walk(Paths.get("input")).forEach(file -> {
			if(!file.toString().endsWith(".jar")) {
				return;
			}

			String modName = file.toString().replaceAll("input/", "").replaceAll(".jar", "");

			System.out.println("=== Transforming " + modName + " ===");

			try {
				transformMod(modName, mappings, intermediaryMappings);
			} catch(Exception e) {
				System.err.println("Transformation failed, going on to next mod: ");

				e.printStackTrace();
			}
		});
	}

	public static void transformMod(String mod,
			TsrgMappings mappings,
			IMappingProvider intermediaryMappings) throws Exception {
		System.out.println("Remapping " + mod + " (srg -> official)");
		remap(new InvertedTsrgMappings(mappings),
				Paths.get("input/" + mod + ".jar"),
				Paths.get("temp/" + mod + "+official.jar"),
				Paths.get("data/1.14.4+srg.jar"));

		System.out.println("Remapping " + mod + " (official -> intermediary)");
		remap(intermediaryMappings,
				Paths.get("temp/" + mod + "+official.jar"),
				Paths.get("temp/" + mod + "+intermediary.jar"),
				Paths.get("data/1.14.4+official.jar"));

		// Now scan for annotations, strip them, and replace them with pointers.

		Path input = Paths.get("temp/" + mod + "+intermediary.jar");
		Path output = Paths.get("output/" + mod + ".jar");

		URI uri = new URI("jar:" + input.toUri().toString());
		FileSystem fs = null;
		boolean shouldClose = false;

		try {
			fs = FileSystems.getFileSystem(uri);
		} catch(FileSystemNotFoundException e) {
		}

		if(fs == null) {
			fs = FileSystems.newFileSystem(uri, Collections.emptyMap());
			shouldClose = true;
		}

		OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(output).build();
		outputConsumer.addNonClassFiles(input);

		List<Map.Entry<String, ObjectHolder>> generatedObjectHolderEntries =
				new ArrayList<>(); // shimName -> ObjectHolder
		List<Map.Entry<String, String>> staticEventRegistrars =
				new ArrayList<>(); // shimName -> baseName
		List<Map.Entry<String, EventBusSubscriber>> eventBusSubscribers =
				new ArrayList<>(); // basename -> EventBusSubscriber

		AtomicReference<String> modName = new AtomicReference<>();

		Files.walkFileTree(fs.getPath("/"), new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
					throws IOException {
				String name = file.toString();

				if(name.endsWith(".class")) {
					String baseName = name.substring(0, name.length() - ".class".length());
					byte[] content = Files.readAllBytes(file);

					ClassReader reader = new ClassReader(content);
					ClassNode node = new ClassNode();

					List<ObjectHolder> objectHolders = new ArrayList<>();
					List<SubscribeEvent> subscribeEvents = new ArrayList<>();

					AccessTransformations accessTransformations = new AccessTransformations();

					Consumer<String> modConsumer = modId -> {
						System.out.println("Class " + baseName + " has @Mod annotation: " + modId);

						modName.set(baseName);
					};

					AnnotationProcessor scanner = new AnnotationProcessor(node, modConsumer);
					ObjectHolderScanner objectHolderScanner =
							new ObjectHolderScanner(scanner, holder -> {
								objectHolders.add(holder);

								accessTransformations.addFieldTransformation(
										holder.getField(), AccessTransformation.DEFINALIZE);
							});

					EventHandlerScanner eventHandlerScanner = new EventHandlerScanner(
							objectHolderScanner,
							subscriber
							-> {
								// System.out.println(subscriber);

								eventBusSubscribers.add(new AbstractMap.SimpleImmutableEntry<>(
										baseName, subscriber));
							},
							subscribeEvent -> {
								// System.out.println(subscribeEvent);

								subscribeEvents.add(subscribeEvent);

								accessTransformations.setClassTransformation(
										AccessTransformation.MAKE_PUBLIC);

								accessTransformations.addMethodTransformation(
										subscribeEvent.getMethod(),
										subscribeEvent.getMethodDescriptor(),
										AccessTransformation.MAKE_PUBLIC);
							});

					reader.accept(eventHandlerScanner, ClassReader.EXPAND_FRAMES);

					ClassWriter writer = new ClassWriter(0);
					AccessTransformer accessTransformer =
							new AccessTransformer(writer, accessTransformations);

					node.accept(accessTransformer);

					objectHolders.forEach(entry -> {
						ClassWriter shimWriter = new ClassWriter(0);
						String shimName =
								ObjectHolderGenerator.generate(baseName, entry, shimWriter);

						generatedObjectHolderEntries.add(
								new AbstractMap.SimpleImmutableEntry<>(shimName, entry));

						outputConsumer.accept("/" + shimName, shimWriter.toByteArray());
					});

					HashMap<String, SubscribeEvent> subscribeEventShims = new HashMap<>();

					subscribeEvents.forEach(entry -> {
						ClassWriter shimWriter = new ClassWriter(0);

						if((entry.getAccess() & Opcodes.ACC_STATIC) == 0) {
							System.err.println(
									"Instance subscribe events are not supported yet, skipping: " +
									baseName + "::" + entry.getMethod());

							return;
						}

						String shimName =
								SubscribeEventGenerator.generate(baseName, entry, shimWriter);

						if(subscribeEventShims.containsKey(shimName)) {
							throw new UnsupportedOperationException(
									"FIXME: Two @SubscribeEvent shims have the same name! This should be handled by Patchwork, it's a bug!");
						}

						subscribeEventShims.put(shimName, entry);

						outputConsumer.accept("/" + shimName, shimWriter.toByteArray());
					});

					if(!subscribeEventShims.isEmpty()) {
						ClassWriter shimWriter = new ClassWriter(0);
						String shimName = StaticEventRegistrarGenerator.generate(
								baseName, subscribeEventShims.entrySet(), shimWriter);

						outputConsumer.accept("/" + shimName, shimWriter.toByteArray());

						staticEventRegistrars.add(
								new AbstractMap.SimpleImmutableEntry<>(shimName, baseName));
					}

					outputConsumer.accept(baseName, writer.toByteArray());
				}

				return FileVisitResult.CONTINUE;
			}
		});

		ClassWriter initializerWriter = new ClassWriter(0);

		String initializerName = "patchwork_generated" + modName.get() + "Initializer";
		ForgeInitializerGenerator.generate(modName.get(),
				initializerName,
				staticEventRegistrars,
				eventBusSubscribers,
				generatedObjectHolderEntries,
				initializerWriter);

		outputConsumer.accept("/" + initializerName, initializerWriter.toByteArray());

		outputConsumer.close();

		if(shouldClose) {
			fs.close();
		}

		uri = new URI("jar:" + output.toUri().toString());
		fs = FileSystems.newFileSystem(uri, Collections.emptyMap());

		Path manifestPath = fs.getPath("/META-INF/mods.toml");

		FileConfig toml = FileConfig.of(manifestPath);
		toml.load();

		Map<String, Object> map = toml.valueMap();

		System.out.println("Raw: " + map);

		ModManifest manifest = ModManifest.parse(map);

		// System.out.println("Parsed: " + manifest);

		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		JsonObject fabric = ModManifestConverter.convertToFabric(manifest);

		JsonObject entrypoints = new JsonObject();
		JsonArray entrypoint = new JsonArray();

		entrypoint.add(initializerName.replace('/', '.'));
		entrypoints.add("patchwork", entrypoint);

		fabric.add("entrypoints", entrypoints);

		String json = gson.toJson(fabric);

		Path fabricModJson = fs.getPath("/fabric.mod.json");

		try {
			Files.delete(fabricModJson);
		} catch(IOException ignored) {
		}

		Files.write(fabricModJson, json.getBytes(StandardCharsets.UTF_8));

		System.out.println(json);

		Files.delete(manifestPath);
		Files.delete(fs.getPath("pack.mcmeta"));

		fs.close();

		// Late entrypoints
		// https://github.com/CottonMC/Cotton/blob/master/modules/cotton-datapack/src/main/java/io/github/cottonmc/cotton/datapack/mixins/MixinCottonInitializerServer.java
	}

	private static void remap(IMappingProvider mappings, Path input, Path output, Path... classpath)
			throws IOException {
		TinyRemapper remapper = TinyRemapper.newRemapper()
										.withMappings(mappings)
										.rebuildSourceFilenames(true)
										.build();

		OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(output).build();

		try {
			outputConsumer.addNonClassFiles(input, NonClassCopyMode.FIX_META_INF, remapper);

			remapper.readClassPath(classpath);
			remapper.readInputs(input);
			remapper.apply(outputConsumer);
		} finally {
			outputConsumer.close();
			remapper.finish();
		}
	}
}
