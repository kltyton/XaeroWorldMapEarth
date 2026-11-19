package io.github.kltyton.xaeroearth.client.chunkmap;

import com.mojang.logging.LogUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import io.github.kltyton.xaeroearth.client.chunkmap.geometry.DetailedGeometry;
import org.lwjgl.system.MemoryUtil;

/** Persistent cache for native tile geometry and height data. */
public final class NativeTileCache {
    private static final int MAGIC = 0x4155494E;
    private static final int PROJECTION_MAGIC = 0x45504d44;
    private static final int VERSION = 1;
    private static final int HEIGHT_COUNT = 32 * 32;
    private static final int MAX_MODEL_BYTES = 64 * 1024 * 1024;
    private static final long FIXED_FILE_BYTES = Integer.BYTES * 5L + HEIGHT_COUNT * Integer.BYTES;
    private static final Pattern SHA256_KEY = Pattern.compile("[0-9a-f]{64}");

    private final Path directory;
    private final AtomicBoolean writeFailureLogged = new AtomicBoolean();
    private static final ConcurrentHashMap<Path, IndexWrites> INDEX_WRITES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Path, Object> DIRECTORY_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Path, LayerSessions> LAYER_SESSIONS = new ConcurrentHashMap<>();
    private static final class IndexWrites { long sequence; int pending; }
    private static final class LayerSessions { int count; FileChannel channel; FileLock shared; }
    private boolean layerSession;

    public NativeTileCache(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    void openLayerSession() {
        synchronized (DIRECTORY_LOCKS.computeIfAbsent(directory, ignored -> new Object())) {
            if (layerSession) throw new IllegalStateException("Tile cache session already opened");
            layerSession = true;
            var state = LAYER_SESSIONS.computeIfAbsent(directory, ignored -> new LayerSessions());
            if (state.count++ != 0) return;
            try {
                Files.createDirectories(directory);
                state.channel = FileChannel.open(directory.resolve(".layers.lock"), StandardOpenOption.CREATE,
                        StandardOpenOption.READ, StandardOpenOption.WRITE);
                state.shared = state.channel.tryLock(0, Long.MAX_VALUE, true);
                if (state.shared == null || !state.shared.isShared()) {
                    if (state.shared != null) state.shared.release();
                    state.shared = null; state.channel.close(); state.channel = null;
                }
            } catch (IOException | SecurityException | OverlappingFileLockException failure) {
                if (state.channel != null) try { state.channel.close(); }
                catch (IOException cleanup) {
                    LogUtils.getLogger().debug("Cannot close native tile cache lock: {}", cleanup.toString());
                }
                state.channel = null; state.shared = null;
                LogUtils.getLogger().debug("Native tile cache cleanup lock unavailable: {}", failure.toString());
            }
        }
    }

    void closeLayerSession() {
        synchronized (DIRECTORY_LOCKS.computeIfAbsent(directory, ignored -> new Object())) {
            if (!layerSession) return;
            layerSession = false;
            var state = LAYER_SESSIONS.get(directory);
            if (state == null || --state.count != 0) return;
            LAYER_SESSIONS.remove(directory, state);
            if (state.channel == null || state.shared == null) return;
            try (var channel = state.channel) {
                state.shared.release();
                try (var exclusive = channel.tryLock()) {
                    if (exclusive != null) pruneUnreferencedLayers();
                }
            } catch (IOException | SecurityException | OverlappingFileLockException failure) {
                LogUtils.getLogger().debug("Native tile cache cleanup unavailable: {}", failure.toString());
            }
        }
    }

    /** Run only when no cooperating client has this cache open. */
    private void pruneUnreferencedLayers() throws IOException {
        var used = new HashSet<String>();
        try (var directories = Files.list(directory)) {
            for (var layer : directories.filter(path -> path.getFileName().toString().startsWith("latest-")
                    && Files.isDirectory(path)).toList()) {
                try (var refs = Files.list(layer)) {
                    for (var ref : refs.filter(path -> path.getFileName().toString().endsWith(".key")).toList()) {
                        var references = readReferences(ref);
                        if (references.isEmpty()) return;
                        for (var entry : references) used.add(entry.key());
                    }
                }
            }
        }
        try (var files = Files.list(directory)) {
            for (var file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".tile")) continue;
                String key = name.substring(0, name.length() - 5);
                if (!SHA256_KEY.matcher(key).matches() || used.contains(key)) continue;
                Files.deleteIfExists(file);
                Files.deleteIfExists(directory.resolve(key + ".projection"));
            }
        }
    }

    public record Hit(String key, ChunkTiles.NativeTile tile) { }
    record LayerHit(Hit hit, int mask) { }
    private record LayerReference(String key, int mask) { }
    final class IndexTicket implements AutoCloseable {
        private final Path reference;
        private final IndexWrites state;
        private final long sequence;
        private final AtomicBoolean closed = new AtomicBoolean();
        private IndexTicket(Path reference, IndexWrites state, long sequence) {
            this.reference = reference; this.state = state; this.sequence = sequence;
        }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            INDEX_WRITES.computeIfPresent(reference, (ignored, current) -> {
                if (current != state) return current;
                synchronized (current) { return --current.pending == 0 ? null : current; }
            });
        }
    }

    IndexTicket reserveLayers(ChunkTiles.Tile tile, int ceiling) {
        Path reference = referenceFor(tile, ceiling);
        long[] sequence = new long[1];
        IndexWrites state = INDEX_WRITES.compute(reference, (ignored, current) -> {
            if (current == null) current = new IndexWrites();
            synchronized (current) { sequence[0] = ++current.sequence; current.pending++; }
            return current;
        });
        return new IndexTicket(reference, state, sequence[0]);
    }

    public synchronized Hit commit(String key, ChunkTiles.NativeTile incoming, int ceiling) {
        return publish(new Hit(key, incoming), ceiling, false);
    }

    /** Stores a model before the client publishes its complete set of visible layers. */
    synchronized boolean writeUnpublished(String key, ChunkTiles.NativeTile incoming) {
        return writeIfPossible(key, incoming);
    }

    synchronized Hit reuse(Hit hit, int ceiling) {
        return publish(hit, ceiling, true);
    }

    private Hit publish(Hit hit, int ceiling, boolean alreadyStored) {
        String key = hit.key();
        ChunkTiles.NativeTile incoming = hit.tile();
        validateKey(key);
        if (incoming == null) throw new NullPointerException("tile");
        if (incoming.key() == null) throw new IllegalArgumentException("tile key is required");
        int mask = availableMask(incoming.heights());
        if (mask != 15) {
            var previous = readLatest(incoming.key(), ceiling);
            if (previous.isPresent()) {
                int previousMask = availableMask(previous.get().tile().heights());
                if (previousMask != mask && (previousMask & mask) == mask) return previous.get();
            }
        }
        if (!alreadyStored) write(key, incoming);
        remember(key, incoming.key(), ceiling);
        return hit;
    }

    public Optional<Hit> readLatest(ChunkTiles.Tile tile, int ceiling) {
        return readLatest(tile, ceiling, false);
    }

    Optional<Hit> readLatestMetadata(ChunkTiles.Tile tile, int ceiling) {
        return readLatest(tile, ceiling, true);
    }

    private Optional<Hit> readLatest(ChunkTiles.Tile tile, int ceiling, boolean metadataOnly) {
        Path reference = referenceFor(tile, ceiling);
        try {
            var refs = readReferences(reference);
            if (refs.isEmpty()) return Optional.empty();
            String key = refs.get(0).key();
            return (metadataOnly ? readMetadata(key, tile) : read(key, tile)).map(model -> new Hit(key, model));
        } catch (IOException | SecurityException failure) {
            LogUtils.getLogger().debug("Native tile index unavailable for {}: {}", tile, failure.toString());
            return Optional.empty();
        }
    }

    List<LayerHit> readLatestLayersMetadata(ChunkTiles.Tile tile, int ceiling) {
        try {
            var result = new ArrayList<LayerHit>();
            for (var reference : readReferences(referenceFor(tile, ceiling))) {
                var model = readMetadata(reference.key(), tile);
                if (model.isEmpty()) return List.of();
                int mask = reference.mask() & availableMask(model.get().heights());
                if (mask != 0) result.add(new LayerHit(new Hit(reference.key(), model.get()), mask));
            }
            return List.copyOf(result);
        } catch (IOException | SecurityException failure) {
            LogUtils.getLogger().debug("Native tile layer index unavailable for {}: {}", tile, failure.toString());
            return List.of();
        }
    }

    private static List<LayerReference> readReferences(Path reference) throws IOException {
        if (!Files.isRegularFile(reference) || Files.size(reference) > 300) return List.of();
        String text = Files.readString(reference, java.nio.charset.StandardCharsets.US_ASCII);
        if (SHA256_KEY.matcher(text).matches()) return List.of(new LayerReference(text, 15));
        String[] lines = text.split("\\n");
        if (lines.length < 2 || lines.length > 5 || !lines[0].equals("layers-v1")) return List.of();
        var result = new ArrayList<LayerReference>();
        int used = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.length() != 66 || line.charAt(64) != ' ' || !SHA256_KEY.matcher(line.substring(0, 64)).matches())
                return List.of();
            int mask = Character.digit(line.charAt(65), 16);
            if (mask <= 0 || (used & mask) != 0) return List.of();
            used |= mask;
            result.add(new LayerReference(line.substring(0, 64), mask));
        }
        return List.copyOf(result);
    }

    void rememberLayers(IndexTicket ticket, ChunkTiles.Tile tile, int ceiling, List<LayerHit> layers) {
        if (layers.isEmpty() || layers.size() > 4) throw new IllegalArgumentException("Invalid tile layers");
        if (!ticket.reference.equals(referenceFor(tile, ceiling))) throw new IllegalArgumentException("Wrong tile index ticket");
        synchronized (ticket.state) {
            if (ticket.sequence != ticket.state.sequence || ticket.closed.get()) return;
            synchronized (DIRECTORY_LOCKS.computeIfAbsent(directory, ignored -> new Object())) {
                writeLayerIndex(tile, ceiling, layers);
            }
        }
    }

    private void writeLayerIndex(ChunkTiles.Tile tile, int ceiling, List<LayerHit> layers) {
        Path reference = referenceFor(tile, ceiling), temporary = null;
        try {
            var text = new StringBuilder("layers-v1\n");
            int used = 0;
            for (var layer : layers) {
                String key = layer.hit().key();
                validateKey(key);
                if (layer.mask() <= 0 || layer.mask() > 15 || (used & layer.mask()) != 0
                        || !Files.isRegularFile(fileFor(key))) return;
                used |= layer.mask();
                text.append(key).append(' ').append(Integer.toHexString(layer.mask())).append('\n');
            }
            Files.createDirectories(reference.getParent());
            temporary = Files.createTempFile(reference.getParent(), ".tile-index-", ".tmp");
            Files.writeString(temporary, text, java.nio.charset.StandardCharsets.US_ASCII);
            Files.move(temporary, reference, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
        } catch (IOException | SecurityException failure) {
            LogUtils.getLogger().debug("Native tile layer index write failed for {}: {}", tile, failure.toString());
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); }
            catch (IOException | SecurityException failure) {
                LogUtils.getLogger().debug("Native tile layer index cleanup failed: {}", failure.toString());
            }
        }
    }

    Optional<ChunkTiles.NativeTile> readMetadata(String key, ChunkTiles.Tile tile) {
        validateKey(key);
        if (tile == null) throw new NullPointerException("tile");
        try (var channel = FileChannel.open(fileFor(key), StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < FIXED_FILE_BYTES || size > FIXED_FILE_BYTES + MAX_MODEL_BYTES) return Optional.empty();
            ByteBuffer header = ByteBuffer.allocate(Integer.BYTES * 5);
            while (header.hasRemaining()) if (channel.read(header) < 0) return Optional.empty();
            header.flip();
            if (header.getInt() != MAGIC || header.getInt() != VERSION
                    || header.getInt() != tile.x() || header.getInt() != tile.z()) return Optional.empty();
            int length = header.getInt();
            if (length < 0 || length > MAX_MODEL_BYTES || size != FIXED_FILE_BYTES + length) return Optional.empty();
            channel.position(Integer.BYTES * 5L + length);
            ByteBuffer encodedHeights = ByteBuffer.allocate(HEIGHT_COUNT * Integer.BYTES);
            while (encodedHeights.hasRemaining()) if (channel.read(encodedHeights) < 0) return Optional.empty();
            encodedHeights.flip();
            int[] heights = new int[HEIGHT_COUNT];
            encodedHeights.asIntBuffer().get(heights);
            return Optional.of(new ChunkTiles.NativeTile(tile, new byte[0], heights));
        } catch (IOException | SecurityException failure) {
            LogUtils.getLogger().debug("Native tile metadata unavailable for {}: {}", key, failure.toString());
            return Optional.empty();
        }
    }

    public synchronized void remember(String key, ChunkTiles.Tile tile, int ceiling) {
        validateKey(key);
        Path reference = referenceFor(tile, ceiling), temporary = null;
        try {
            if (!Files.isRegularFile(fileFor(key))) return;
            String previous = Files.isRegularFile(reference) && Files.size(reference) == 64
                    ? Files.readString(reference, java.nio.charset.StandardCharsets.US_ASCII) : null;
            if (key.equals(previous)) return;
            Files.createDirectories(reference.getParent());
            temporary = Files.createTempFile(reference.getParent(), ".tile-index-", ".tmp");
            Files.writeString(temporary, key, java.nio.charset.StandardCharsets.US_ASCII);
            Files.move(temporary, reference, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            if (previous != null && !previous.equals(key) && SHA256_KEY.matcher(previous).matches()) {
                boolean referenced = false;
                try (var layers = Files.list(directory)) {
                    for (var layer : layers.filter(path -> path.getFileName().toString().startsWith("latest-")).toList()) {
                        Path other = layer.resolve(reference.getFileName());
                        if (Files.isRegularFile(other) && Files.size(other) == 64
                                && previous.equals(Files.readString(other, java.nio.charset.StandardCharsets.US_ASCII))) {
                            referenced = true; break;
                        }
                    }
                }
                if (!referenced) {
                    Files.deleteIfExists(fileFor(previous));
                    Files.deleteIfExists(directory.resolve(previous + ".projection"));
                }
            }
        } catch (IOException | SecurityException failure) {
            LogUtils.getLogger().debug("Native tile index write failed for {}: {}", tile, failure.toString());
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException | SecurityException failure) {
                    LogUtils.getLogger().debug("Native tile index cleanup failed: {}", failure.toString());
                }
            }
        }
    }

    private Path referenceFor(ChunkTiles.Tile tile, int ceiling) {
        return directory.resolve("latest-" + ceiling).resolve(tile.x() + "_" + tile.z() + ".key");
    }

    Optional<ChunkTileSource.ProjectionMetadata> readProjectionMetadata(String key, ChunkTiles.Tile tile) {
        validateKey(key);
        Path path = directory.resolve(key + ".projection");
        try {
            if (!Files.isRegularFile(path)) return Optional.empty();
            long size = Files.size(path);
            if (size != 48 && size != 72) return Optional.empty();
            try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                byte[] bytes = in.readNBytes((int) size - Integer.BYTES);
                var checksum = new CRC32(); checksum.update(bytes);
                if (bytes.length != size - Integer.BYTES || (int) checksum.getValue() != in.readInt()) return Optional.empty();
                var data = ByteBuffer.wrap(bytes);
                int magic = data.getInt(), version = data.getInt();
                if (magic != PROJECTION_MAGIC || (version != 1 || size != 48) && (version != 2 || size != 72)
                        || data.getInt() != tile.x() || data.getInt() != tile.z()) return Optional.empty();
                float minX = data.getFloat(), minY = data.getFloat(), minZ = data.getFloat();
                float maxX = data.getFloat(), maxY = data.getFloat(), maxZ = data.getFloat();
                int flags = data.getInt();
                if (!Float.isFinite(minX) || !Float.isFinite(minY) || !Float.isFinite(minZ)
                        || !Float.isFinite(maxX) || !Float.isFinite(maxY) || !Float.isFinite(maxZ)
                        || minX > maxX || minY > maxY || minZ > maxZ || (flags & ~(version == 1 ? 3 : 7)) != 0)
                    return Optional.empty();
                DetailedGeometry.Bounds animatedBounds = null;
                if (version == 2) {
                    float ax = data.getFloat(), ay = data.getFloat(), az = data.getFloat();
                    float bx = data.getFloat(), by = data.getFloat(), bz = data.getFloat();
                    if ((flags & 4) != 0) {
                        if ((flags & 1) == 0 || !Float.isFinite(ax) || !Float.isFinite(ay) || !Float.isFinite(az)
                                || !Float.isFinite(bx) || !Float.isFinite(by) || !Float.isFinite(bz)
                                || ax > bx || ay > by || az > bz
                                || ax < minX || ay < minY || az < minZ || bx > maxX || by > maxY || bz > maxZ)
                            return Optional.empty();
                        animatedBounds = new DetailedGeometry.Bounds(ax, ay, az, bx, by, bz);
                    }
                }
                return Optional.of(new ChunkTileSource.ProjectionMetadata(
                        new DetailedGeometry.Bounds(minX, minY, minZ, maxX, maxY, maxZ),
                        (flags & 1) != 0, (flags & 2) != 0, animatedBounds));
            }
        } catch (IOException | SecurityException failure) {
            LogUtils.getLogger().debug("Native tile projection metadata unavailable for {}: {}", key, failure.toString());
            return Optional.empty();
        }
    }

    synchronized void writeProjectionMetadata(String key, ChunkTiles.Tile tile, ChunkTileSource.ProjectionMetadata metadata) {
        validateKey(key);
        Path temporary = null;
        try {
            if (!Files.isRegularFile(fileFor(key))) return;
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, ".projection-metadata-", ".tmp");
            try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                var bounds = metadata.bounds();
                var data = ByteBuffer.allocate(68);
                data.putInt(PROJECTION_MAGIC).putInt(2).putInt(tile.x()).putInt(tile.z());
                data.putFloat(bounds.minX()).putFloat(bounds.minY()).putFloat(bounds.minZ());
                data.putFloat(bounds.maxX()).putFloat(bounds.maxY()).putFloat(bounds.maxZ());
                var animated = metadata.animatedBounds();
                data.putInt((metadata.animated() ? 1 : 0) | (metadata.translucent() ? 2 : 0)
                        | (animated != null ? 4 : 0));
                if (animated == null) for (int i = 0; i < 6; i++) data.putFloat(0);
                else {
                    data.putFloat(animated.minX()).putFloat(animated.minY()).putFloat(animated.minZ());
                    data.putFloat(animated.maxX()).putFloat(animated.maxY()).putFloat(animated.maxZ());
                }
                var checksum = new CRC32(); checksum.update(data.array());
                out.write(data.array()); out.writeInt((int) checksum.getValue());
            }
            Files.move(temporary, directory.resolve(key + ".projection"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
        } catch (IOException | SecurityException failure) {
            LogUtils.getLogger().debug("Native tile projection metadata write failed for {}: {}", key, failure.toString());
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); }
            catch (IOException | SecurityException failure) {
                LogUtils.getLogger().debug("Native tile projection metadata cleanup failed: {}", failure.toString());
            }
        }
    }

    public Optional<ChunkTiles.NativeTile> read(String sha256Key, ChunkTiles.Tile expected) {
        validateKey(sha256Key);
        if (expected == null) throw new NullPointerException("expected");

        Path file = fileFor(sha256Key);
        try {
            if (!Files.isRegularFile(file)) return Optional.empty();
            long fileSize = Files.size(file);
            if (fileSize < FIXED_FILE_BYTES || fileSize > FIXED_FILE_BYTES + MAX_MODEL_BYTES) {
                return miss(sha256Key, "invalid file size");
            }

            try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                    return miss(sha256Key, "invalid header");
                }

                int x = input.readInt();
                int z = input.readInt();
                int modelLength = input.readInt();
                if (x != expected.x() || z != expected.z()
                        || modelLength < 0 || modelLength > MAX_MODEL_BYTES
                        || fileSize != FIXED_FILE_BYTES + modelLength) {
                    return miss(sha256Key, "tile or payload mismatch");
                }

                byte[] model = new byte[modelLength];
                input.readFully(model);
                int[] heights = new int[HEIGHT_COUNT];
                for (int i = 0; i < heights.length; i++) heights[i] = input.readInt();
                if (input.read() != -1) return miss(sha256Key, "trailing data");

                return Optional.of(new ChunkTiles.NativeTile(expected, model, heights));
            }
        } catch (IOException | SecurityException failure) {
            LogUtils.getLogger().debug("Native tile cache miss for {}: {}", sha256Key, failure.toString());
            return Optional.empty();
        }
    }

    /** Read evicted geometry into native memory without allocating another large Java array. */
    Optional<DetailedGeometry> readGeometry(String sha256Key, ChunkTiles.Tile expected) throws IOException {
        validateKey(sha256Key);
        if (expected == null) throw new NullPointerException("expected");
        Path file = fileFor(sha256Key);
        boolean decoding = false;
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < FIXED_FILE_BYTES || size > FIXED_FILE_BYTES + MAX_MODEL_BYTES) return Optional.empty();
            ByteBuffer header = ByteBuffer.allocate(Integer.BYTES * 5);
            while (header.hasRemaining()) if (channel.read(header) < 0) return Optional.empty();
            header.flip();
            if (header.getInt() != MAGIC || header.getInt() != VERSION
                    || header.getInt() != expected.x() || header.getInt() != expected.z()) return Optional.empty();
            int length = header.getInt();
            if (length < 0 || length > MAX_MODEL_BYTES || size != FIXED_FILE_BYTES + length) return Optional.empty();
            ByteBuffer encoded = MemoryUtil.memAlloc(Math.max(1, length));
            boolean transferred = false;
            try {
                encoded.limit(length);
                while (encoded.hasRemaining()) if (channel.read(encoded) < 0) return Optional.empty();
                encoded.flip();
                decoding = true;
                DetailedGeometry geometry = DetailedGeometry.decodeOwned(encoded);
                transferred = true;
                return Optional.of(geometry);
            } finally { if (!transferred) MemoryUtil.memFree(encoded); }
        } catch (IOException failure) {
            if (decoding) throw failure;
            LogUtils.getLogger().debug("Native tile cache miss for {}: {}", sha256Key, failure.toString());
            return Optional.empty();
        } catch (SecurityException failure) {
            LogUtils.getLogger().debug("Native tile cache miss for {}: {}", sha256Key, failure.toString());
            return Optional.empty();
        }
    }

    public void write(String sha256Key, ChunkTiles.NativeTile tile) {
        writeIfPossible(sha256Key, tile);
    }

    private boolean writeIfPossible(String sha256Key, ChunkTiles.NativeTile tile) {
        validateKey(sha256Key);
        if (tile == null) throw new NullPointerException("tile");
        if (tile.key() == null) throw new IllegalArgumentException("tile key is required");
        if (tile.model() == null || tile.model().length > MAX_MODEL_BYTES) {
            throw new IllegalArgumentException("model must be at most 64 MiB");
        }
        if (tile.heights() == null || tile.heights().length != HEIGHT_COUNT) {
            throw new IllegalArgumentException("tile must contain exactly 1024 heights");
        }

        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, ".native-tile-", ".tmp");
            try (var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeInt(tile.key().x());
                output.writeInt(tile.key().z());
                output.writeInt(tile.model().length);
                output.write(tile.model());
                for (int height : tile.heights()) output.writeInt(height);
            }

            Files.move(temporary, fileFor(sha256Key), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            writeFailureLogged.set(false);
            return true;
        } catch (IOException | SecurityException failure) {
            if (writeFailureLogged.compareAndSet(false, true))
                LogUtils.getLogger().warn("Native tile cache write failed for {}", sha256Key, failure);
            else LogUtils.getLogger().debug("Native tile cache write failed for {}: {}", sha256Key, failure.toString());
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException | SecurityException cleanupFailure) {
                    LogUtils.getLogger().debug("Native tile cache temp cleanup failed: {}", cleanupFailure.toString());
                }
            }
        }
    }

    private static int availableMask(int[] heights) {
        if (heights == null || heights.length != HEIGHT_COUNT) {
            throw new IllegalArgumentException("tile must contain exactly 1024 heights");
        }
        int mask = 0;
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            boolean known = true;
            int x0 = (quadrant & 1) * 16, z0 = (quadrant >> 1) * 16;
            for (int z = z0; z < z0 + 16 && known; z++) {
                for (int x = x0; x < x0 + 16; x++) {
                    if (heights[z * 32 + x] == 32767) { known = false; break; }
                }
            }
            if (known) mask |= 1 << quadrant;
        }
        return mask;
    }

    private Path fileFor(String sha256Key) {
        return directory.resolve(sha256Key + ".tile");
    }

    private static void validateKey(String sha256Key) {
        if (sha256Key == null || !SHA256_KEY.matcher(sha256Key).matches()) {
            throw new IllegalArgumentException("cache key must be 64 lowercase hexadecimal characters");
        }
    }

    private static Optional<ChunkTiles.NativeTile> miss(String key, String reason) {
        LogUtils.getLogger().debug("Native tile cache miss for {}: {}", key, reason);
        return Optional.empty();
    }
}
