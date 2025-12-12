package net.quiltmc.users.malythist.randomchunks;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.*;
import net.minecraft.block.sapling.SaplingBlock;
import net.minecraft.block.sign.AbstractSignBlock;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.random.RandomGenerator;
import net.minecraft.world.World;
import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.qsl.base.api.entrypoint.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class RandomChunksMod implements ModInitializer {

	public static final String MOD_ID = "randomchunks";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final Map<UUID, ChunkPos> LAST_PLAYER_CHUNK = new HashMap<>();
	private static final List<BlockState> POSSIBLE_BLOCK_STATES = new ArrayList<>();
	private static final Map<RegistryKey<World>, Set<ChunkPos>> PROCESSED_CHUNKS = new HashMap<>();
	private static boolean ENABLED = false;
	private static String LAST_USED_BLOCK = "—";

	private static void onWorldTick(ServerWorld world) {
		if (!ENABLED) return;
		if (POSSIBLE_BLOCK_STATES.isEmpty()) return;

		for (ServerPlayerEntity player : world.getPlayers()) {
			if (!player.isAlive()) continue;

			UUID id = player.getUuid();
			ChunkPos currentChunk = player.getChunkPos();
			ChunkPos lastChunk = LAST_PLAYER_CHUNK.get(id);

			if (lastChunk == null || !lastChunk.equals(currentChunk)) {
				LAST_PLAYER_CHUNK.put(id, currentChunk);
				if (!isChunkProcessed(world, currentChunk)) {
					randomizeChunk(world, currentChunk);
				}
			}
		}
	}

	private static boolean isChunkProcessed(ServerWorld world, ChunkPos pos) {
		Set<ChunkPos> set = PROCESSED_CHUNKS.get(world.getRegistryKey());
		if (set == null) return false;
		return set.contains(pos);
	}

	private static void markChunkProcessed(ServerWorld world, ChunkPos pos) {
		PROCESSED_CHUNKS
			.computeIfAbsent(world.getRegistryKey(), key -> new HashSet<>())
			.add(new ChunkPos(pos.x, pos.z));
	}

	private static void randomizeChunk(ServerWorld world, ChunkPos chunkPos) {
		if (POSSIBLE_BLOCK_STATES.isEmpty()) return;

		// на всякий случай ещё раз проверим
		if (isChunkProcessed(world, chunkPos)) return;

		int startX = chunkPos.getStartX();
		int endX = chunkPos.getEndX();
		int startZ = chunkPos.getStartZ();
		int endZ = chunkPos.getEndZ();

		int bottomY = world.getBottomY();
		int topY = world.getTopY();

		RandomGenerator random = world.getRandom();
		BlockState chosenState = chooseRandomState(random);
		LAST_USED_BLOCK = Registries.BLOCK.getId(chosenState.getBlock()).toString();

		for (int x = startX; x <= endX; x++) {
			for (int z = startZ; z <= endZ; z++) {
				for (int y = bottomY; y < topY; y++) {
					BlockPos pos = new BlockPos(x, y, z);
					BlockState current = world.getBlockState(pos);

					if (isSourceExcluded(current)) continue;

					if (shouldRemoveInsteadOfReplace(current)) {
						world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
					} else if (!current.isOf(chosenState.getBlock())) {
						world.setBlockState(pos, chosenState, 3);
					}
				}
			}
		}

		markChunkProcessed(world, chunkPos);
	}

	private static BlockState chooseRandomState(RandomGenerator random) {
		int size = POSSIBLE_BLOCK_STATES.size();
		if (size == 0) return Blocks.STONE.getDefaultState();
		int index = random.nextInt(size);
		return POSSIBLE_BLOCK_STATES.get(index);
	}

	private static boolean isSourceExcluded(BlockState state) {
		if (state.isAir()) return true;
		if (state.isOf(Blocks.WATER)) return true;
		if (state.isOf(Blocks.LAVA)) return true;
		return state.isOf(Blocks.END_PORTAL_FRAME);
	}

	private static boolean shouldRemoveInsteadOfReplace(BlockState state) {
		Block block = state.getBlock();

		if (isSnowLike(block)) return true;
		if (isSmallPlant(state, block)) return true;
		if (isVine(block)) return true;
		if (isAmethystBud(block)) return true;
		if (isCropLike(state, block)) return true;
		if (isCoralToRemove(state)) return true;
		if (isDecorLike(block)) return true;

		// паутина и прочий «мусор»
		return block == Blocks.COBWEB;
	}

	private static boolean isSnowLike(Block block) {
		return block == Blocks.SNOW;
	}

	private static boolean isSmallPlant(BlockState state, Block block) {
		if (block == Blocks.GRASS_BLOCK || block == Blocks.TALL_GRASS ||
			block == Blocks.FERN || block == Blocks.LARGE_FERN) return true;

		if (block == Blocks.MOSS_CARPET) return true;

		if (state.isIn(BlockTags.FLOWERS)) return true;

		if (state.isIn(BlockTags.SAPLINGS)) return true;

		return block == Blocks.RED_MUSHROOM || block == Blocks.BROWN_MUSHROOM;
	}

	private static boolean isVine(Block block) {
		if (block == Blocks.VINE) return true;
		if (block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT) return true;
		if (block == Blocks.TWISTING_VINES || block == Blocks.TWISTING_VINES_PLANT) return true;
		return block == Blocks.WEEPING_VINES || block == Blocks.WEEPING_VINES_PLANT;
	}

	private static boolean isAmethystBud(Block block) {
		if (block == Blocks.SMALL_AMETHYST_BUD) return true;
		if (block == Blocks.MEDIUM_AMETHYST_BUD) return true;
		if (block == Blocks.LARGE_AMETHYST_BUD) return true;
		return block == Blocks.AMETHYST_CLUSTER;
	}

	private static boolean isCropLike(BlockState state, Block block) {
		if (state.isIn(BlockTags.CROPS)) return true;
		if (block == Blocks.COCOA) return true;
		if (block == Blocks.SWEET_BERRY_BUSH) return true;
		return block == Blocks.NETHER_WART;
	}

	private static boolean isCoralToRemove(BlockState state) {
		return state.isIn(BlockTags.CORALS) && !state.isIn(BlockTags.CORAL_BLOCKS);
	}

	private static boolean isDecorLike(Block block) {
		if (block instanceof BedBlock) return true;

		if (block instanceof CarpetBlock) return true;

		if (block instanceof AbstractBannerBlock) return true;

		if (block instanceof AbstractSignBlock) return true;

		if (block instanceof AbstractSkullBlock) return true;

		if (block instanceof TorchBlock) return true;
		if (block instanceof WallTorchBlock) return true;
		if (block instanceof RedstoneTorchBlock) return true;
		if (block instanceof RedstoneWallTorchBlock) return true;
		if (block instanceof LanternBlock) return true;

		if (block instanceof KelpStemBlock) return true;
		if (block instanceof KelpBodyBlock) return true;
		if (block instanceof SeagrassBlock) return true;
		return block instanceof TallSeagrassBlock;
	}

	private static final Set<Block> FORBIDDEN_BLOCKS = Set.of(
		Blocks.BEDROCK, Blocks.BARRIER, Blocks.LIGHT, Blocks.STRUCTURE_BLOCK,
		Blocks.STRUCTURE_VOID, Blocks.WATER, Blocks.LAVA, Blocks.END_PORTAL_FRAME,
		Blocks.END_PORTAL, Blocks.NETHER_PORTAL, Blocks.TRIPWIRE, Blocks.COBWEB,
		Blocks.GRAVEL, Blocks.SUSPICIOUS_GRAVEL, Blocks.SAND, Blocks.SUSPICIOUS_SAND,
		Blocks.IRON_BARS, Blocks.POWDER_SNOW, Blocks.CHAIN, Blocks.BAMBOO,
		Blocks.BAMBOO_SAPLING, Blocks.SCULK_VEIN, Blocks.CRIMSON_ROOTS,
		Blocks.HANGING_ROOTS, Blocks.WARPED_ROOTS, Blocks.BELL, Blocks.TURTLE_EGG,
		Blocks.FROGSPAWN, Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WIRE,
		Blocks.REDSTONE_WALL_TORCH, Blocks.COMPARATOR, Blocks.REPEATER,
		Blocks.TRIPWIRE_HOOK, Blocks.DAYLIGHT_DETECTOR, Blocks.LIGHTNING_ROD,
		Blocks.DECORATED_POT, Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM,
		Blocks.SEA_PICKLE, Blocks.SEAGRASS, Blocks.FIRE, Blocks.RAIL,
		Blocks.ACTIVATOR_RAIL, Blocks.POWERED_RAIL, Blocks.DETECTOR_RAIL,
		Blocks.LADDER, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.CRIMSON_FUNGUS,
		Blocks.WARPED_FUNGUS, Blocks.FLOWERING_AZALEA, Blocks.AZALEA, Blocks.CONDUIT,
		Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE, Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE,
		Blocks.SPORE_BLOSSOM, Blocks.SHORT_GRASS, Blocks.CHORUS_PLANT,
		Blocks.CHORUS_FLOWER, Blocks.SPAWNER, Blocks.TRIAL_SPAWNER, Blocks.HEAVY_CORE,
		Blocks.BREWING_STAND, Blocks.MELON_STEM, Blocks.ATTACHED_MELON_STEM,
		Blocks.PUMPKIN_STEM, Blocks.ATTACHED_PUMPKIN_STEM, Blocks.GLOW_LICHEN,
		Blocks.ENCHANTING_TABLE, Blocks.LILY_PAD, Blocks.CAULDRON, Blocks.LAVA_CAULDRON,
		Blocks.WATER_CAULDRON, Blocks.SNIFFER_EGG, Blocks.VAULT, Blocks.PITCHER_CROP,
		Blocks.ICE, Blocks.SUGAR_CANE, Blocks.NETHER_SPROUTS, Blocks.PITCHER_PLANT,
		Blocks.PISTON_HEAD, Blocks.PINK_PETALS, Blocks.BUBBLE_COLUMN, Blocks.END_ROD,
		Blocks.SMALL_DRIPLEAF, Blocks.CACTUS, Blocks.JIGSAW, Blocks.SCAFFOLDING,
		Blocks.MOVING_PISTON
	);

	private static final Set<Class<? extends Block>> FORBIDDEN_CLASSES = Set.of(
		PressurePlateBlock.class, ButtonBlock.class, LeverBlock.class,
		CoralParentBlock.class, CoralBlock.class, CoralFanBlock.class, CoralBlockBlock.class,
		ChestBlock.class, FallingBlock.class, ConcretePowderBlock.class, LeavesBlock.class,
		FlowerBlock.class, TallFlowerBlock.class, SaplingBlock.class, CropBlock.class,
		DoorBlock.class, CandleBlock.class, CandleCakeBlock.class, FlowerPotBlock.class,
		HorizontalConnectingBlock.class, FenceBlock.class, FenceGateBlock.class,
		MushroomBlock.class, WallBlock.class, ShulkerBoxBlock.class, CommandBlock.class,
		RailBlock.class, TrapdoorBlock.class
	);

	private static boolean isForbiddenTarget(Block block) {
		if (FORBIDDEN_BLOCKS.contains(block)) {
			return true;
		}

		return FORBIDDEN_CLASSES.stream().anyMatch(clazz -> clazz.isInstance(block));
	}

	@Override
	public void onInitialize(ModContainer mod) {
		initBlockPool();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			registerCommands(dispatcher);
		});

		ServerTickEvents.START_WORLD_TICK.register(RandomChunksMod::onWorldTick);

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			ENABLED = false;
			LAST_PLAYER_CHUNK.clear();
			PROCESSED_CHUNKS.clear();
		});

		LOGGER.info("[{}] initialized", MOD_ID);
	}

	private void initBlockPool() {
		POSSIBLE_BLOCK_STATES.clear();
		for (Block block : Registries.BLOCK) {
			BlockState state = block.getDefaultState();
			if (state.isAir()) continue;
			if (isForbiddenTarget(block)) continue;
			if (shouldRemoveInsteadOfReplace(state)) continue;
			POSSIBLE_BLOCK_STATES.add(state);
		}
		LOGGER.info("[{}] allowed block states: {}", MOD_ID, POSSIBLE_BLOCK_STATES.size());
	}

	private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(
			CommandManager.literal(MOD_ID)
				.then(CommandManager.literal("start")
					.executes(ctx -> {
						ServerCommandSource source = ctx.getSource();
						ServerPlayerEntity player = source.getPlayer();

						if (player == null) {
							source.sendError(Text.literal("Эту команду может вызывать только игрок."));
							return 0;
						}

						ENABLED = true;

						ChunkPos chunkPos = player.getChunkPos();
						LAST_PLAYER_CHUNK.put(player.getUuid(), chunkPos);

						ServerWorld world = (ServerWorld) player.getWorld();
						if (!isChunkProcessed(world, chunkPos)) {
							randomizeChunk(world, chunkPos);
						}

						source.sendFeedback(
							() -> Text.literal("RandomChunks: мод активирован. Текущий чанк перерандомлен."),
							true
						);
						return 1;
					})
				)
				.then(CommandManager.literal("stop")
					.executes(ctx -> {
						ENABLED = false;
						ctx.getSource().sendFeedback(
							() -> Text.literal("RandomChunks: мод деактивирован."),
							true
						);
						return 1;
					})
				)
				.then(CommandManager.literal("info")
					.executes(ctx -> {
						String status = ENABLED ? "активен" : "выключен";
						int poolSize = POSSIBLE_BLOCK_STATES.size();

						ctx.getSource().sendFeedback(
							() -> Text.literal(
								"RandomChunks: мод " + status +
									". Блоков в пуле: " + poolSize +
									". Последний использованный блок: " + LAST_USED_BLOCK
							),
							false
						);
						return 1;
					})
				)
		);
	}
}
