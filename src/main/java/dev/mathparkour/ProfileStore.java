package dev.mathparkour;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Small JSON store. Progress is kept inside the world's save folder. */
public final class ProfileStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<Path, PlayerProfile> CACHE = new HashMap<>();

    private ProfileStore() {}

    public static synchronized PlayerProfile get(MinecraftServer server, UUID playerId) {
        Path file = profilePath(server, playerId);
        PlayerProfile cached = CACHE.get(file);
        if (cached != null) return cached;

        PlayerProfile loaded = new PlayerProfile();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                PlayerProfile fromDisk = GSON.fromJson(reader, PlayerProfile.class);
                if (fromDisk != null) loaded = fromDisk;
            } catch (Exception e) {
                MathParkourMod.LOGGER.warn("Could not read Math Parkour profile {}; starting with a fresh profile.", file, e);
            }
        }
        loaded.normalize();
        CACHE.put(file, loaded);
        return loaded;
    }

    public static synchronized void save(MinecraftServer server, UUID playerId) {
        PlayerProfile profile = get(server, playerId);
        write(profilePath(server, playerId), profile);
    }

    public static synchronized void flushAll() {
        for (Map.Entry<Path, PlayerProfile> entry : CACHE.entrySet()) {
            write(entry.getKey(), entry.getValue());
        }
        CACHE.clear();
    }

    private static Path profilePath(MinecraftServer server, UUID playerId) {
        return server.getSavePath(WorldSavePath.ROOT)
                .resolve("mathparkour")
                .resolve("players")
                .resolve(playerId + ".json")
                .toAbsolutePath();
    }

    private static void write(Path file, PlayerProfile profile) {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(temp)) {
                GSON.toJson(profile, writer);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            MathParkourMod.LOGGER.error("Could not save Math Parkour profile {}", file, e);
        }
    }
}
