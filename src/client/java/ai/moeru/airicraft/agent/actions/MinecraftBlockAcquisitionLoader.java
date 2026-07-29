package ai.moeru.airicraft.agent.actions;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Minecraft shell that captures loaded loot-table resources and immutable registry metadata.
 */
public final class MinecraftBlockAcquisitionLoader {
	private MinecraftBlockAcquisitionLoader() {
	}

	public static BlockAcquisitionIndex load(ResourceManager resourceManager) {
		List<ToolCandidate> toolCandidates = toolCandidates();
		ArrayList<BlockLootTableSource> sources = new ArrayList<>();
		Registries.BLOCK.getEntrySet().stream()
			.sorted(Map.Entry.comparingByKey(Comparator.comparing(key -> key.getValue().toString())))
			.forEach(entry -> captureBlockSource(resourceManager, entry.getValue(), entry.getKey().getValue().toString(), toolCandidates)
				.ifPresent(sources::add));
		return BlockLootTableCompiler.compile(sources, itemTags());
	}

	private static Optional<BlockLootTableSource> captureBlockSource(
		ResourceManager resourceManager,
		Block block,
		String blockId,
		List<ToolCandidate> toolCandidates
	) {
		return block.getLootTableKey().flatMap(lootTableKey -> {
			Identifier lootTableId = lootTableKey.getValue();
			Identifier resourceId = Identifier.of(
				lootTableId.getNamespace(),
				"loot_table/" + lootTableId.getPath() + ".json"
			);
			Optional<String> json = readResource(resourceManager, resourceId);
			if (json.isEmpty()) {
				return Optional.empty();
			}
			BlockState state = block.getDefaultState();
			List<String> suitableTools = state.isToolRequired()
				? toolCandidates.stream()
					.filter(candidate -> candidate.stack().isSuitableFor(state))
					.map(ToolCandidate::itemId)
					.toList()
				: List.of();
			return Optional.of(new BlockLootTableSource(
				blockId,
				lootTableId.toString(),
				json.get(),
				state.isToolRequired(),
				suitableTools
			));
		});
	}

	private static List<ToolCandidate> toolCandidates() {
		return Registries.ITEM.getEntrySet().stream()
			.sorted(Map.Entry.comparingByKey(Comparator.comparing(key -> key.getValue().toString())))
			.map(entry -> new ToolCandidate(entry.getKey().getValue().toString(), new ItemStack(entry.getValue())))
			.filter(candidate -> candidate.stack().get(DataComponentTypes.TOOL) != null)
			.toList();
	}

	private static Map<String, List<String>> itemTags() {
		LinkedHashMap<String, List<String>> tags = new LinkedHashMap<>();
		Registries.ITEM.streamTags()
			.sorted(Comparator.comparing(named -> named.getTag().id().toString()))
			.forEach(named -> tags.put(
				named.getTag().id().toString(),
				named.stream()
					.map(entry -> Registries.ITEM.getId(entry.value()).toString())
					.distinct()
					.sorted()
					.toList()
			));
		return Collections.unmodifiableMap(tags);
	}

	private static Optional<String> readResource(ResourceManager resourceManager, Identifier resourceId) {
		if (resourceManager != null) {
			Optional<Resource> loaded = resourceManager.getResource(resourceId);
			if (loaded.isPresent()) {
				try (InputStream input = loaded.get().getInputStream()) {
					return Optional.of(new String(input.readAllBytes(), StandardCharsets.UTF_8));
				}
				catch (IOException ignored) {
					return Optional.empty();
				}
			}
		}
		String classpathName = "data/" + resourceId.getNamespace() + "/" + resourceId.getPath();
		ClassLoader classLoader = MinecraftBlockAcquisitionLoader.class.getClassLoader();
		try (InputStream input = classLoader.getResourceAsStream(classpathName)) {
			return input == null
				? Optional.empty()
				: Optional.of(new String(input.readAllBytes(), StandardCharsets.UTF_8));
		}
		catch (IOException ignored) {
			return Optional.empty();
		}
	}

	private record ToolCandidate(String itemId, ItemStack stack) {
	}
}
