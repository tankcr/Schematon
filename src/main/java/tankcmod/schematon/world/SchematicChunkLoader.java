package tankcmod.schematon.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.common.world.ForgeChunkManager;
import org.slf4j.Logger;
import tankcmod.schematon.Schematon;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side chunk-forcing helper scoped to an active export or paste job ID.
 * Ensures target chunks reach {@link ChunkStatus#FULL} while processed and
 * releases forced tickets immediately when advancing or cleaning up a job.
 */
public final class SchematicChunkLoader {
    private static final Logger LOGGER = LogUtils.getLogger();

    private record ActiveTicket(ServerLevel level, UUID ownerId, Set<Long> forcedChunks) {}

    private static final Map<UUID, ActiveTicket> ACTIVE_JOBS = new ConcurrentHashMap<>();

    private SchematicChunkLoader() {}

    /**
     * Ensures the chunk containing {@code worldPos} is force-loaded and generated/loaded
     * through {@link ChunkStatus#FULL} for the given job.
     */
    public static boolean ensureChunkLoaded(ServerLevel level, UUID jobId, BlockPos worldPos) {
        if (level == null || jobId == null || worldPos == null) {
            return false;
        }

        ChunkPos chunkPos = new ChunkPos(worldPos);
        long packedPos = chunkPos.toLong();

        ActiveTicket ticket = ACTIVE_JOBS.computeIfAbsent(
                jobId,
                id -> new ActiveTicket(level, id, new HashSet<>())
        );

        if (ticket.level != level) {
            releaseAll(jobId);
            ticket = new ActiveTicket(level, jobId, new HashSet<>());
            ACTIVE_JOBS.put(jobId, ticket);
        }

        if (ticket.forcedChunks.add(packedPos)) {
            boolean forced = ForgeChunkManager.forceChunk(
                    level,
                    Schematon.MODID,
                    ticket.ownerId,
                    chunkPos.x,
                    chunkPos.z,
                    true,
                    true
            );
            if (!forced) {
                LOGGER.debug("ForgeChunkManager refused forceChunk for {} at [{}, {}] in job {}",
                        level.dimension().location(), chunkPos.x, chunkPos.z, jobId);
            }
        }

        return level.getChunkSource().getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, true) != null;
    }

    /**
     * Releases any forced chunks for {@code jobId} that are no longer needed,
     * keeping only {@code keepPos}'s chunk if non-null.
     */
    public static void releaseExcept(UUID jobId, BlockPos keepPos) {
        ActiveTicket ticket = ACTIVE_JOBS.get(jobId);
        if (ticket == null || ticket.forcedChunks.isEmpty()) {
            return;
        }

        long keepPacked = (keepPos != null) ? ChunkPos.asLong(keepPos.getX() >> 4, keepPos.getZ() >> 4) : Long.MIN_VALUE;
        Set<Long> toRelease = new HashSet<>();
        for (long packed : ticket.forcedChunks) {
            if (packed != keepPacked) {
                toRelease.add(packed);
            }
        }

        for (long packed : toRelease) {
            unforceChunk(ticket, packed);
            ticket.forcedChunks.remove(packed);
        }
    }

    /**
     * Releases all forced chunks registered under {@code jobId}.
     */
    public static void releaseAll(UUID jobId) {
        if (jobId == null) return;
        ActiveTicket ticket = ACTIVE_JOBS.remove(jobId);
        if (ticket == null) return;

        for (long packed : ticket.forcedChunks) {
            unforceChunk(ticket, packed);
        }
        ticket.forcedChunks.clear();
    }

    private static void unforceChunk(ActiveTicket ticket, long packedChunkPos) {
        int chunkX = ChunkPos.getX(packedChunkPos);
        int chunkZ = ChunkPos.getZ(packedChunkPos);
        try {
            ForgeChunkManager.forceChunk(
                    ticket.level,
                    Schematon.MODID,
                    ticket.ownerId,
                    chunkX,
                    chunkZ,
                    false,
                    true
            );
        } catch (Exception e) {
            LOGGER.warn("Failed releasing forced chunk [{}, {}] for job {}", chunkX, chunkZ, ticket.ownerId, e);
        }
    }
}