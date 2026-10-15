package dev.mathparkour;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.openspacedrepetition.Rating;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Math Parkour — endless runner variant with three doors. Supports focused-table (all 6's/etc), mixed, and typed answer mode.
 */
public final class MathParkourMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("mathparkour");

    private static final int STAGE_SPACING = 8;
    private static final int GATE_OFFSET = 1;
    private static final int GAP_LENGTH = 3;
    private static final List<MathQuestion> CATALOG = MathQuestion.catalog();
    private static final Random RANDOM = new Random();
    private static final Map<UUID, CourseRun> ACTIVE_RUNS = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> TYPED_MODE = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> ACTIVE_CONFIDENCE = new ConcurrentHashMap<>();

    private static final Block[] LANE_COLORS = {
            Blocks.RED_WOOL,
            Blocks.YELLOW_WOOL,
            Blocks.LIME_WOOL
    };

    // Shop catalog
    private static final Map<String, ShopItem> SHOP = new HashMap<>();
    static {
        SHOP.put("pet_wolf", new ShopItem("Wolf Pet", 50, "Summon a friendly wolf pet near you.", ItemKind.PET));
        SHOP.put("toy_firework", new ShopItem("Firework Rocket", 15, "A fun firework toy.", ItemKind.TOY));
        SHOP.put("toy_ball", new ShopItem("Ball (Slime)", 10, "A bouncy toy.", ItemKind.TOY));
    }

    // Hub triggers (base block position => action). buildHub populates this.
    private static final Map<BlockPos, HubAction> HUB_ACTIONS = new HashMap<>();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(MathParkourMod::tickPlayers);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            ProfileStore.flushAll();
            ACTIVE_RUNS.clear();
            TYPED_MODE.clear();
            ACTIVE_CONFIDENCE.clear();
            HUB_ACTIONS.clear();
        });
        LOGGER.info("Math Parkour initialized. Use /mathparkour hub to build the launch area.");
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("mathparkour")
                .then(CommandManager.literal("start").executes(context -> {
                    ServerPlayerEntity player = getPlayer(context.getSource());
                    if (player == null) return 0;
                    startCourse(player, GameMode.MIXED, null);
                    return 1;
                }))
                .then(CommandManager.literal("start")
                        .then(CommandManager.argument("mode", StringArgumentType.word()).executes(context -> {
                            ServerPlayerEntity player = getPlayer(context.getSource());
                            if (player == null) return 0;
                            String requested = StringArgumentType.getString(context, "mode");
                            StartSpec spec = parseStartSpec(requested);
                            startCourse(player, spec.mode(), spec.focusNumber());
                            return 1;
                        })))
                .then(CommandManager.literal("mode")
                        .then(CommandManager.argument("modeName", StringArgumentType.word()).executes(context -> {
                            ServerPlayerEntity player = getPlayer(context.getSource());
                            if (player == null) return 0;
                            String modeName = StringArgumentType.getString(context, "modeName");
                            if ("typed".equalsIgnoreCase(modeName)) {
                                TYPED_MODE.put(player.getUuid(), true);
                                ACTIVE_CONFIDENCE.putIfAbsent(player.getUuid(), 2);
                                player.sendMessage(Text.literal("Typed answer mode enabled. Use /mathparkour answer <number> during a run."), false);
                            } else if ("doors".equalsIgnoreCase(modeName)) {
                                TYPED_MODE.put(player.getUuid(), false);
                                player.sendMessage(Text.literal("Door answer mode enabled."), false);
                            } else {
                                player.sendMessage(Text.literal("Unknown mode. Use 'typed' or 'doors'."), false);
                            }
                            return 1;
                        })))
                .then(CommandManager.literal("answer")
                        .then(CommandManager.argument("value", StringArgumentType.word()).executes(context -> {
                            ServerPlayerEntity player = getPlayer(context.getSource());
                            if (player == null) return 0;
                            String raw = StringArgumentType.getString(context, "value");
                            try {
                                handleTypedAnswer(player, Integer.parseInt(raw));
                            } catch (NumberFormatException e) {
                                player.sendMessage(Text.literal("Use a whole number, for example: /mathparkour answer 24"), false);
                            }
                            return 1;
                        })))
                .then(CommandManager.literal("conf")
                        .then(CommandManager.argument("level", IntegerArgumentType.integer(1, 3)).executes(context -> {
                            ServerPlayerEntity player = getPlayer(context.getSource());
                            if (player == null) return 0;
                            int level = IntegerArgumentType.getInteger(context, "level");
                            ACTIVE_CONFIDENCE.put(player.getUuid(), level);
                            player.sendMessage(Text.literal("Confidence set to " + level + " (1 = low, 2 = medium, 3 = high)."), false);
                            return 1;
                        })))
                .then(CommandManager.literal("hub").executes(context -> {
                    ServerPlayerEntity player = getPlayer(context.getSource());
                    if (player == null) return 0;
                    buildHub(player);
                    return 1;
                }))
                .then(CommandManager.literal("hint").executes(context -> {
                    ServerPlayerEntity player = getPlayer(context.getSource());
                    if (player == null) return 0;
                    sendHint(player);
                    return 1;
                }))
                .then(CommandManager.literal("stats").executes(context -> {
                    ServerPlayerEntity player = getPlayer(context.getSource());
                    if (player == null) return 0;
                    sendStats(player);
                    return 1;
                }))
                .then(CommandManager.literal("stop").executes(context -> {
                    ServerPlayerEntity player = getPlayer(context.getSource());
                    if (player == null) return 0;
                    CourseRun removed = ACTIVE_RUNS.remove(player.getUuid());
                    player.sendMessage(Text.literal(removed == null
                            ? "You do not have an active Math Parkour run."
                            : "Math Parkour stopped. The built gates remain in the world."), false);
                    return 1;
                }))
                .then(CommandManager.literal("shop").executes(context -> {
                    ServerPlayerEntity player = getPlayer(context.getSource());
                    if (player == null) return 0;
                    showShop(player);
                    return 1;
                }))
                .then(CommandManager.literal("buy")
                        .then(CommandManager.argument("item", StringArgumentType.word()).executes(context -> {
                            ServerPlayerEntity player = getPlayer(context.getSource());
                            if (player == null) return 0;
                            String itemId = StringArgumentType.getString(context, "item");
                            buyItem(player, itemId);
                            return 1;
                        })))
                .then(CommandManager.literal("summon")
                        .then(CommandManager.argument("item", StringArgumentType.word()).executes(context -> {
                            ServerPlayerEntity player = getPlayer(context.getSource());
                            if (player == null) return 0;
                            String itemId = StringArgumentType.getString(context, "item");
                            summonOwned(player, itemId);
                            return 1;
                        })))
                .then(CommandManager.literal("pets").executes(context -> {
                    ServerPlayerEntity player = getPlayer(context.getSource());
                    if (player == null) return 0;
                    listOwned(player);
                    return 1;
                }))
        );
    }

    private static void handleTypedAnswer(ServerPlayerEntity player, int answer) {
        CourseRun run = ACTIVE_RUNS.get(player.getUuid());
        if (run == null) {
            player.sendMessage(Text.literal("No active run. Use /mathparkour start <mode>."), false);
            return;
        }
        int confidence = ACTIVE_CONFIDENCE.getOrDefault(player.getUuid(), 2);
        if (answer == run.current.question.answer()) {
            boolean isFirstReviewForThisPrompt = run.recordedCardIds.add(run.current.question.id());
            PlayerProfile profile = ProfileStore.get(player.getServer(), player.getUuid());
            profile.correctChoices++;
            int confidenceBonus = switch (confidence) {
                case 1 -> 0;
                case 2 -> 3;
                case 3 -> 6;
                default -> 3;
            };
            int earned = 5 + confidenceBonus + (isFirstReviewForThisPrompt ? 5 : 0);
            earnPoints(profile, earned);
            if (isFirstReviewForThisPrompt) {
                profile.firstTryCorrect++;
                FsrsReviews.rate(profile, run.current.question.id(), Rating.GOOD);
            }
            ProfileStore.save(player.getServer(), player.getUuid());
            player.sendMessage(Text.literal("Correct! +" + earned + " points (confidence " + confidence + "). " + run.current.question.prompt().replace("?", Integer.toString(answer)) + "  The safe path continues."), false);
            player.sendMessage(Text.literal("Explanation: " + explain(run.current.question)), false);
            run.gateResolved = false;
            run.promptShownStage = -1;
            run.fallPending = false;
            advanceRun(player, run);
            return;
        }

        PlayerProfile profile = ProfileStore.get(player.getServer(), player.getUuid());
        boolean isFirstReviewForThisPrompt = run.recordedCardIds.add(run.current.question.id());
        profile.wrongChoices++;
        if (isFirstReviewForThisPrompt) {
            profile.firstTryWrong++;
            FsrsReviews.rate(profile, run.current.question.id(), Rating.AGAIN);
        }
        ProfileStore.save(player.getServer(), player.getUuid());
        run.fallPending = true;
        player.sendMessage(Text.literal("Wrong answer! The correct answer was " + run.current.question.answer() + ". Hint: " + run.current.question.hint()), false);
        player.sendMessage(Text.literal("Review: " + explain(run.current.question)), false);
    }

    private static ServerPlayerEntity getPlayer(ServerCommandSource source) {
        Entity entity = source.getEntity();
        if (entity instanceof ServerPlayerEntity player) return player;
        source.sendError(Text.literal("Run this command as a player in a world."));
        return null;
    }

    private static StartSpec parseStartSpec(String raw) {
        if (raw == null) return new StartSpec(GameMode.MIXED, null);
        String s = raw.trim().toLowerCase();
        switch (s) {
            case "math": return new StartSpec(GameMode.MATH, null);
            case "squares": return new StartSpec(GameMode.SQUARES, null);
            case "mixed": return new StartSpec(GameMode.MIXED, null);
        }
        if (s.matches("^\\d{1,2}$")) {
            try {
                int n = Integer.parseInt(s);
                return new StartSpec(GameMode.MATH, n);
            } catch (NumberFormatException ignored) { }
        }
        if (s.matches("^(table|times|t)[:_\\-]?\\d{1,2}$")) {
            int n = Integer.parseInt(s.replaceAll("\\D+", ""));
            return new StartSpec(GameMode.MATH, n);
        }
        if (s.matches("^(sq|square|squares)[:_\\-]?\\d{1,2}$")) {
            int n = Integer.parseInt(s.replaceAll("\\D+", ""));
            return new StartSpec(GameMode.SQUARES, n);
        }
        return new StartSpec(GameMode.MIXED, null);
    }

    private record StartSpec(GameMode mode, Integer focusNumber) {}

    private static void buildHub(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        int baseX = player.getBlockX() + 5;
        int baseY = player.getBlockY() - 1;
        int baseZ = player.getBlockZ() - 5;

        HUB_ACTIONS.clear();

        int platformX1 = baseX - 6;
        int platformX2 = baseX + 12;
        int platformZ1 = baseZ - 6;
        int platformZ2 = baseZ + 12;
        for (int x = platformX1; x <= platformX2; x++) {
            for (int z = platformZ1; z <= platformZ2; z++) {
                place(world, x, baseY, z, Blocks.STONE_BRICKS);
            }
        }

        for (int x = baseX - 2; x <= baseX + 2; x++) {
            for (int z = baseZ - 2; z <= baseZ + 2; z++) {
                place(world, x, baseY + 1, z, Blocks.YELLOW_CONCRETE);
            }
        }

        int plateY = baseY + 1;

        BlockPos mathPlate = new BlockPos(baseX - 4, plateY, baseZ + 4);
        place(world, mathPlate.getX(), mathPlate.getY(), mathPlate.getZ(), Blocks.RED_CONCRETE);
        place(world, mathPlate.getX(), mathPlate.getY() + 1, mathPlate.getZ(), Blocks.STONE_PRESSURE_PLATE);
        HUB_ACTIONS.put(mathPlate, HubAction.START_MATH);

        BlockPos squarePlate = new BlockPos(baseX - 1, plateY, baseZ + 4);
        place(world, squarePlate.getX(), squarePlate.getY(), squarePlate.getZ(), Blocks.YELLOW_CONCRETE);
        place(world, squarePlate.getX(), squarePlate.getY() + 1, squarePlate.getZ(), Blocks.STONE_PRESSURE_PLATE);
        HUB_ACTIONS.put(squarePlate, HubAction.START_SQUARES);

        BlockPos mixedPlate = new BlockPos(baseX + 5, plateY, baseZ + 4);
        place(world, mixedPlate.getX(), mixedPlate.getY(), mixedPlate.getZ(), Blocks.BLUE_CONCRETE);
        place(world, mixedPlate.getX(), mixedPlate.getY() + 1, mixedPlate.getZ(), Blocks.STONE_PRESSURE_PLATE);
        HUB_ACTIONS.put(mixedPlate, HubAction.START_MIXED);

        BlockPos shopPlate = new BlockPos(baseX - 4, plateY, baseZ - 4);
        place(world, shopPlate.getX(), shopPlate.getY(), shopPlate.getZ(), Blocks.PURPLE_CONCRETE);
        place(world, shopPlate.getX(), shopPlate.getY() + 1, shopPlate.getZ(), Blocks.STONE_PRESSURE_PLATE);
        HUB_ACTIONS.put(shopPlate, HubAction.SHOP);

        BlockPos petPlate = new BlockPos(baseX + 5, plateY, baseZ - 4);
        place(world, petPlate.getX(), petPlate.getY(), petPlate.getZ(), Blocks.PINK_CONCRETE);
        place(world, petPlate.getX(), petPlate.getY() + 1, petPlate.getZ(), Blocks.STONE_PRESSURE_PLATE);
        HUB_ACTIONS.put(petPlate, HubAction.SUMMON_WOLF);

        teleport(player, baseX + 0.5, baseY + 2.0, baseZ + 0.5);
        player.sendMessage(Text.literal("Hub ready. Stand on a colored pressure plate to start a course or open the shop."), false);
        player.sendMessage(Text.literal("Red = math, yellow = squares, blue = mixed, purple = shop, pink = pet summon."), false);
    }

    private static void startCourse(ServerPlayerEntity player, GameMode mode, Integer focusNumber) {
        MinecraftServer server = player.getServer();
        ServerWorld world = player.getServerWorld();
        PlayerProfile profile = ProfileStore.get(server, player.getUuid());

        int baseX = player.getBlockX() + 8;
        int centerZ = player.getBlockZ();
        int endX = baseX + STAGE_SPACING + 5;
        int floorY = chooseFloorY(world, baseX, endX, centerZ, player.getBlockY());
        if (floorY < 0) {
            player.sendMessage(Text.literal("There isn't enough vertical room to build the course here. Try a lower, flatter area."), false);
            return;
        }

        CourseRun run = new CourseRun(baseX, floorY, centerZ, world.getRegistryKey(), mode, focusNumber);
        run.current = generateRound(profile, mode, focusNumber, run);
        run.typedMode = TYPED_MODE.getOrDefault(player.getUuid(), false);
        spawnGate(world, run, 0);

        ACTIVE_RUNS.put(player.getUuid(), run);
        teleport(player, baseX + 1.5, floorY + 1.0, centerZ + 0.5);

        String modeLabel = mode.label + (focusNumber == null ? "" : (" (" + focusNumber + ")"));
        player.sendMessage(Text.literal("Math Parkour endless run started in " + modeLabel + " mode: " + (run.typedMode ? "type the answer with /mathparkour answer <number>." : "walk through the colored doors.") + " Wrong answers drop into lava and send you to the checkpoint."), false);
        player.sendMessage(Text.literal("Use /mathparkour hint or /mathparkour stats. Earn points. Use /mathparkour stop to end the run."), false);
    }

    private static int chooseFloorY(ServerWorld world, int startX, int endX, int centerZ, int playerY) {
        int highest = playerY;
        for (int x = startX; x <= endX; x += 4) {
            for (int z = centerZ - 7; z <= centerZ + 7; z += 3) {
                highest = Math.max(highest, world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z));
            }
        }
        int floorY = Math.max(playerY + 8, highest + 7);
        int upperSafeLimit = world.getTopY() - 10;
        return floorY <= upperSafeLimit ? floorY : -1;
    }

    private static DoorRound generateRound(PlayerProfile profile, GameMode mode, Integer focusNumber, CourseRun run) {
        List<MathQuestion> chosen = chooseQuestions(profile, 1, mode, focusNumber);
        MathQuestion q = chosen.isEmpty() ? MathQuestion.catalog().get(RANDOM.nextInt(MathQuestion.catalog().size())) : chosen.get(0);
        List<Integer> options = MathQuestion.makeOptions(q, RANDOM);
        return new DoorRound(q, options, options.indexOf(q.answer()));
    }

    private static void spawnGate(ServerWorld world, CourseRun run, int stageIndex) {
        int stageX = run.baseX + stageIndex * STAGE_SPACING;
        int gateX = stageX + GATE_OFFSET;
        int centerZ = run.centerZ;
        int floorY = run.floorY;

        for (int x = stageX - 2; x <= stageX + 6; x++) {
            for (int z = centerZ - 8; z <= centerZ + 8; z++) {
                for (int y = floorY - 5; y <= floorY + 5; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!world.getBlockState(pos).isAir()) world.setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
                }
            }
        }

        for (int x = stageX - 1; x <= stageX + 6; x++) {
            for (int z = centerZ - 7; z <= centerZ + 7; z++) {
                place(world, x, floorY - 5, z, Blocks.OBSIDIAN);
            }
        }
        for (int y = floorY - 4; y <= floorY + 1; y++) {
            for (int x = stageX - 1; x <= stageX + 6; x++) {
                place(world, x, y, centerZ - 7, Blocks.OBSIDIAN);
                place(world, x, y, centerZ + 7, Blocks.OBSIDIAN);
            }
            for (int z = centerZ - 7; z <= centerZ + 7; z++) {
                place(world, stageX - 1, y, z, Blocks.OBSIDIAN);
                place(world, stageX + 6, y, z, Blocks.OBSIDIAN);
            }
        }
        for (int x = stageX; x <= stageX + 6; x++) {
            for (int z = centerZ - 6; z <= centerZ + 6; z++) {
                place(world, x, floorY - 4, z, Blocks.LAVA);
            }
        }

        floor(world, stageX, gateX - 1, centerZ - 5, centerZ + 5, floorY, Blocks.STONE_BRICKS);

        for (int z = centerZ - 6; z <= centerZ + 6; z++) {
            for (int y = floorY + 1; y <= floorY + 2; y++) {
                boolean opening = false;
                for (int lane = 0; lane < 3; lane++) {
                    int laneZ = centerZ + laneOffset(lane);
                    if (Math.abs(z - laneZ) <= 1) opening = true;
                }
                if (!opening) place(world, gateX, y, z, Blocks.STONE_BRICKS);
            }
        }

        for (int lane = 0; lane < 3; lane++) {
            int laneZ = centerZ + laneOffset(lane);
            for (int dz = -1; dz <= 1; dz++) place(world, gateX, floorY + 3, laneZ + dz, LANE_COLORS[lane]);
        }

        for (int x = gateX + 1; x <= gateX + GAP_LENGTH; x++) {
            for (int dz = -1; dz <= 1; dz++) place(world, x, floorY, centerZ + laneOffset(run.current.correctLane) + dz, Blocks.STONE_BRICKS);
            place(world, gateX + 2, floorY, centerZ + laneOffset(run.current.correctLane), LANE_COLORS[run.current.correctLane]);
        }

        floor(world, gateX + GAP_LENGTH + 1, gateX + GAP_LENGTH + 2, centerZ - 5, centerZ + 5, floorY, Blocks.STONE_BRICKS);
    }

    private static boolean isPlayerAtGate(ServerPlayerEntity player, CourseRun run) {
        int stageX = run.baseX + run.stage * STAGE_SPACING;
        int gateX = stageX + GATE_OFFSET;
        return player.getX() >= gateX + 0.05 && player.getX() <= gateX + GAP_LENGTH + 0.8
                && player.getY() >= run.floorY && player.getY() <= run.floorY + 4;
    }

    private static void advanceRun(ServerPlayerEntity player, CourseRun run) {
        run.stage++;
        PlayerProfile profile = ProfileStore.get(player.getServer(), player.getUuid());
        run.current = generateRound(profile, run.mode, run.focusNumber, run);
        spawnGate((ServerWorld) player.getServerWorld(), run, run.stage);
    }

    private static void floor(ServerWorld world, int x1, int x2, int z1, int z2, int y, Block block) {
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) place(world, x, y, z, block);
        }
    }

    private static void place(ServerWorld world, int x, int y, int z, Block block) {
        world.setBlockState(new BlockPos(x, y, z), block.getDefaultState(), 3);
    }

    private static void tickPlayers(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            boolean hubHandled = false;
            for (Map.Entry<BlockPos, HubAction> entry : HUB_ACTIONS.entrySet()) {
                BlockPos trigger = entry.getKey();
                if (player.getBlockX() == trigger.getX()
                        && player.getBlockZ() == trigger.getZ()
                        && player.getBlockY() == trigger.getY() + 1) {
                    triggerHubAction(player, entry.getValue());
                    hubHandled = true;
                    break;
                }
            }
            if (hubHandled) continue;

            CourseRun run = ACTIVE_RUNS.get(player.getUuid());
            if (run == null) continue;
            if (!player.getWorld().getRegistryKey().equals(run.dimension)) {
                ACTIVE_RUNS.remove(player.getUuid());
                player.sendMessage(Text.literal("Math Parkour paused because you changed dimensions."), false);
                continue;
            }

            if (player.getY() <= run.floorY - 3.4) {
                boolean questionMiss = run.fallPending;
                int checkpointX = run.baseX + run.stage * STAGE_SPACING + 1;
                teleport(player, checkpointX + 0.5, run.floorY + 1.0, run.centerZ + 0.5);
                run.fallPending = false;
                if (questionMiss) {
                    player.sendMessage(Text.literal("Checkpoint restored. Retry the same fact; this immediate retry won't add a second FSRS review."), false);
                } else {
                    player.sendMessage(Text.literal("Run checkpoint restored."), false);
                }
                continue;
            }

            int stageX = run.baseX + run.stage * STAGE_SPACING;
            int gateX = stageX + GATE_OFFSET;
            if (run.promptShownStage != run.stage
                    && player.getX() >= stageX
                    && player.getX() < gateX
                    && player.getY() >= run.floorY
                    && player.getY() <= run.floorY + 4) {
                showQuestion(player, run.stage, run.current, run.typedMode);
                run.promptShownStage = run.stage;
            }

            if (!run.typedMode && !run.gateResolved && !run.fallPending && isPlayerAtGate(player, run)) {
                int lane = laneAt(player.getZ() - run.centerZ);
                if (lane >= 0) resolveDoor(server, player, run, lane);
            }
        }
    }

    private static int laneOffset(int lane) {
        return switch (lane) {
            case 0 -> -4;
            case 1 -> 0;
            default -> 4;
        };
    }

    private static int laneAt(double relativeZ) {
        if (relativeZ >= -5.4 && relativeZ <= -2.6) return 0;
        if (relativeZ >= -1.4 && relativeZ <= 1.4) return 1;
        if (relativeZ >= 2.6 && relativeZ <= 5.4) return 2;
        return -1;
    }

    private static void showQuestion(ServerPlayerEntity player, int stage, DoorRound round, boolean typedMode) {
        player.sendMessage(Text.literal("Question " + (stage + 1) + ": " + round.question.prompt()), false);
        if (typedMode) {
            player.sendMessage(Text.literal("Type the answer with /mathparkour answer <number>. Set confidence with /mathparkour conf <1|2|3>."), true);
            return;
        }
        player.sendMessage(Text.literal("LEFT / RED: " + round.options.get(0)
                + "    CENTER / YELLOW: " + round.options.get(1)
                + "    RIGHT / GREEN: " + round.options.get(2)), false);
        player.sendMessage(Text.literal("Pick the answer lane. Use /mathparkour hint if you need a clue."), true);
    }

    private static void resolveDoor(MinecraftServer server, ServerPlayerEntity player, CourseRun run, int lane) {
        run.gateResolved = true;
        PlayerProfile profile = ProfileStore.get(server, player.getUuid());
        boolean isFirstReviewForThisPrompt = run.recordedCardIds.add(run.current.question.id());

        if (lane == run.current.correctLane) {
            profile.correctChoices++;
            int earned = 5 + (isFirstReviewForThisPrompt ? 5 : 0);
            earnPoints(profile, earned);
            if (isFirstReviewForThisPrompt) {
                profile.firstTryCorrect++;
                FsrsReviews.rate(profile, run.current.question.id(), Rating.GOOD);
            }
            ProfileStore.save(server, player.getUuid());
            player.sendMessage(Text.literal("Correct! +" + earned + " points. " + run.current.question.prompt().replace("?", Integer.toString(run.current.question.answer())) + "  The safe path continues."), false);
            player.sendMessage(Text.literal("Explanation: " + explain(run.current.question)), false);

            run.gateResolved = false;
            run.promptShownStage = -1;
            run.fallPending = false;
            advanceRun(player, run);
        } else {
            profile.wrongChoices++;
            if (isFirstReviewForThisPrompt) {
                profile.firstTryWrong++;
                FsrsReviews.rate(profile, run.current.question.id(), Rating.AGAIN);
            }
            ProfileStore.save(server, player.getUuid());
            run.fallPending = true;
            player.sendMessage(Text.literal("Wrong door! The floor drops into lava. Hint: " + run.current.question.hint()), false);
            player.sendMessage(Text.literal("Review: " + explain(run.current.question)), false);
        }
    }

    private static void sendHint(ServerPlayerEntity player) {
        CourseRun run = ACTIVE_RUNS.get(player.getUuid());
        if (run == null) {
            player.sendMessage(Text.literal("Start a run with /mathparkour start before asking for a hint."), false);
            return;
        }
        player.sendMessage(Text.literal("Hint: " + run.current.question.hint()), false);
    }

    private static void sendStats(ServerPlayerEntity player) {
        PlayerProfile profile = ProfileStore.get(player.getServer(), player.getUuid());
        int due = FsrsReviews.dueCount(profile, Instant.now());
        player.sendMessage(Text.literal("Math Parkour stats — saved FSRS cards: " + profile.cards.size()
                + "; due now: " + due + "; points: " + profile.points
                + "; first-try correct: " + profile.firstTryCorrect
                + "; first-try wrong: " + profile.firstTryWrong
                + "; all correct choices: " + profile.correctChoices
                + "; wrong choices: " + profile.wrongChoices
                + "; run stages: " + (ACTIVE_RUNS.get(player.getUuid()) != null ? ACTIVE_RUNS.get(player.getUuid()).stage : 0)
                + "; completed courses: " + profile.coursesCompleted), false);
    }

    private static void teleport(ServerPlayerEntity player, double x, double y, double z) {
        player.requestTeleport(x, y, z);
        player.setYaw(-90.0f);
        player.setPitch(0.0f);
    }

    private record DoorRound(MathQuestion question, List<Integer> options, int correctLane) {}

    private static final class CourseRun {
        private final int baseX;
        private final int floorY;
        private final int centerZ;
        private final net.minecraft.registry.RegistryKey<World> dimension;
        private final GameMode mode;
        private final Integer focusNumber;
        private final Set<String> recordedCardIds = new HashSet<>();
        private DoorRound current;
        private int stage = 0;
        private int promptShownStage = -1;
        private boolean fallPending = false;
        private boolean gateResolved = false;
        private boolean typedMode = false;

        private CourseRun(int baseX, int floorY, int centerZ,
                          net.minecraft.registry.RegistryKey<World> dimension, GameMode mode, Integer focusNumber) {
            this.baseX = baseX;
            this.floorY = floorY;
            this.centerZ = centerZ;
            this.dimension = dimension;
            this.mode = mode;
            this.focusNumber = focusNumber;
        }
    }

    private static void earnPoints(PlayerProfile profile, int amount) {
        profile.points += Math.max(0, amount);
    }

    private static String explain(MathQuestion q) {
        if (q.division()) {
            return q.operand1() + " ÷ " + q.operand2() + " = " + q.answer() + " because " + q.answer() + " × " + q.operand2() + " = " + q.operand1() + ".";
        }
        if (q.id().startsWith("sq:")) {
            int n = q.operand1();
            return n + "² = " + q.answer() + " because " + n + " × " + n + " = " + q.answer() + ".";
        }
        return q.operand1() + " × " + q.operand2() + " = " + q.answer() + ".";
    }

    private static void showShop(ServerPlayerEntity player) {
        PlayerProfile profile = ProfileStore.get(player.getServer(), player.getUuid());
        player.sendMessage(Text.literal("Math Parkour Shop — points: " + profile.points), false);
        for (Map.Entry<String, ShopItem> e : SHOP.entrySet()) {
            ShopItem item = e.getValue();
            player.sendMessage(Text.literal(e.getKey() + " — " + item.name + " (" + item.price + " pts): " + item.description), false);
        }
        player.sendMessage(Text.literal("Buy with /mathparkour buy <itemId>. Summon pets with /mathparkour summon <itemId> after purchase."), false);
    }

    private static void buyItem(ServerPlayerEntity player, String itemId) {
        PlayerProfile profile = ProfileStore.get(player.getServer(), player.getUuid());
        ShopItem item = SHOP.get(itemId);
        if (item == null) {
            player.sendMessage(Text.literal("Unknown shop item: " + itemId), false);
            return;
        }
        if (profile.ownedItems.contains(itemId)) {
            player.sendMessage(Text.literal("You already own " + item.name + "."), false);
            return;
        }
        if (profile.points < item.price) {
            player.sendMessage(Text.literal("Not enough points. You have " + profile.points + " and need " + item.price + "."), false);
            return;
        }
        profile.points -= item.price;
        profile.ownedItems.add(itemId);
        ProfileStore.save(player.getServer(), player.getUuid());
        player.sendMessage(Text.literal("Purchased " + item.name + "! Use /mathparkour summon " + itemId + " to activate it."), false);
    }

    private static void listOwned(ServerPlayerEntity player) {
        PlayerProfile profile = ProfileStore.get(player.getServer(), player.getUuid());
        if (profile.ownedItems.isEmpty()) {
            player.sendMessage(Text.literal("You do not own any shop items yet. Earn points and use /mathparkour shop."), false);
            return;
        }
        player.sendMessage(Text.literal("Owned items:"), false);
        for (String id : profile.ownedItems) {
            ShopItem s = SHOP.get(id);
            player.sendMessage(Text.literal(id + " — " + (s != null ? s.name : "(unknown)")), false);
        }
    }

    private static void summonOwned(ServerPlayerEntity player, String itemId) {
        PlayerProfile profile = ProfileStore.get(player.getServer(), player.getUuid());
        if (!profile.ownedItems.contains(itemId)) {
            player.sendMessage(Text.literal("You don't own " + itemId + ". Buy it first with /mathparkour buy " + itemId), false);
            return;
        }
        ShopItem item = SHOP.get(itemId);
        if (item == null) {
            player.sendMessage(Text.literal("Unknown item: " + itemId), false);
            return;
        }
        ServerWorld world = player.getServerWorld();
        switch (item.kind) {
            case PET -> {
                WolfEntity wolf = EntityType.WOLF.create(world);
                if (wolf != null) {
                    wolf.refreshPositionAndAngles(player.getX() + 1.0, player.getY(), player.getZ() + 1.0, 0.0f, 0.0f);
                    wolf.setCustomName(Text.literal("Parkour Pet"));
                    wolf.setPersistent();
                    try {
                        wolf.setOwnerUuid(player.getUuid());
                    } catch (Throwable ignored) { }
                    world.spawnEntity(wolf);
                    player.sendMessage(Text.literal("Your pet has been summoned."), false);
                } else {
                    player.sendMessage(Text.literal("Could not summon pet here."), false);
                }
            }
            case TOY -> {
                ItemStack stack;
                if ("toy_firework".equals(itemId)) stack = new ItemStack(Items.FIREWORK_ROCKET, 1);
                else if ("toy_ball".equals(itemId)) stack = new ItemStack(Items.SLIME_BALL, 1);
                else stack = new ItemStack(Items.STONE, 1);
                if (!player.getInventory().insertStack(stack)) player.dropStack(stack, false);
                player.sendMessage(Text.literal("Toy given. Check your inventory."), false);
            }
            default -> player.sendMessage(Text.literal("This item cannot be summoned."), false);
        }
    }

    private static void triggerHubAction(ServerPlayerEntity player, HubAction action) {
        switch (action) {
            case START_MATH -> startCourse(player, GameMode.MATH, null);
            case START_SQUARES -> startCourse(player, GameMode.SQUARES, null);
            case START_SCIENCE -> startCourse(player, GameMode.MIXED, null);
            case START_MIXED -> startCourse(player, GameMode.MIXED, null);
            case SHOP -> showShop(player);
            case SUMMON_WOLF -> {
                PlayerProfile profile = ProfileStore.get(player.getServer(), player.getUuid());
                if (!profile.ownedItems.contains("pet_wolf")) {
                    player.sendMessage(Text.literal("You need to buy the wolf pet in /mathparkour shop first."), false);
                    return;
                }
                summonOwned(player, "pet_wolf");
            }
        }
    }

    private static final class ShopItem {
        final String name;
        final int price;
        final String description;
        final ItemKind kind;

        ShopItem(String name, int price, String description, ItemKind kind) {
            this.name = name;
            this.price = price;
            this.description = description;
            this.kind = kind;
        }
    }
    private enum ItemKind { PET, TOY }

    private enum HubAction {
        START_MATH,
        START_SQUARES,
        START_SCIENCE,
        START_MIXED,
        SHOP,
        SUMMON_WOLF
    }

    private enum GameMode {
        MATH("math"),
        SQUARES("squares"),
        SCIENCE("science"),
        MIXED("mixed");

        final String label;

        GameMode(String label) {
            this.label = label;
        }

        static GameMode fromString(String value) {
            if (value == null) return MIXED;
            String v = value.trim().toLowerCase();
            return switch (v) {
                case "math" -> MATH;
                case "squares" -> SQUARES;
                case "science" -> SCIENCE;
                case "mixed" -> MIXED;
                default -> MIXED;
            };
        }
    }
}
