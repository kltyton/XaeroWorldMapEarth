package io.github.kltyton.xaeroearth.client.chunkmap.projection;

import com.mojang.logging.LogUtils;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryUtil;

/** Disk storage for projected color or material samples and their shared depth. */
public final class ProjectedTileStore {
    private static final int MAGIC = 0x45505449, VERSION = 3;
    private static final int PIXELS = ProjectedGrid.IMAGE_PIXELS * ProjectedGrid.IMAGE_PIXELS;
    private static final int COLOR_BYTES = PIXELS * 8, DEPTH_BYTES = PIXELS * Float.BYTES;
    private static final int MATERIAL_PLANES = 6;
    private static final long MAX_FILE_BYTES = 2L * (COLOR_BYTES * MATERIAL_PLANES + DEPTH_BYTES);
    private static final long DEFAULT_LIMIT = 1L << 30, PRUNE_INTERVAL_NANOS = 30_000_000_000L;
    private static final long TOUCH_INTERVAL_MILLIS = 300_000L;
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{64}");

    /** Bottom-up color endpoints or material planes, and normalized depth in owned native buffers. */
    public static final class Image implements AutoCloseable {
        public enum ColorFormat { RGBA16F, RGBA8, MATERIAL_RGBA16F }
        private final double cameraX, cameraY, cameraZ;
        private final boolean reverseDepth;
        private final ColorFormat colorFormat;
        private final ByteBuffer low, high, depth;
        private boolean closed;

        private Image(double cameraX, double cameraY, double cameraZ, boolean reverseDepth, ColorFormat colorFormat,
                      ByteBuffer low, ByteBuffer high, ByteBuffer depth) {
            this.cameraX = cameraX; this.cameraY = cameraY; this.cameraZ = cameraZ;
            this.reverseDepth = reverseDepth; this.colorFormat = colorFormat;
            this.low = low; this.high = high; this.depth = depth;
        }

        public static Image allocate(double cameraX, double cameraY, double cameraZ, boolean reverseDepth) {
            return allocate(cameraX, cameraY, cameraZ, reverseDepth, ColorFormat.RGBA16F);
        }

        public static Image allocate(double cameraX, double cameraY, double cameraZ, boolean reverseDepth,
                                     ColorFormat colorFormat) {
            if (!Double.isFinite(cameraX) || !Double.isFinite(cameraY) || !Double.isFinite(cameraZ))
                throw new IllegalArgumentException("Invalid projected tile camera");
            java.util.Objects.requireNonNull(colorFormat);
            ByteBuffer low = null, high = null, depth = null;
            try {
                int colorBytes = colorFormat == ColorFormat.RGBA8 ? PIXELS * 4 : COLOR_BYTES;
                boolean material = colorFormat == ColorFormat.MATERIAL_RGBA16F;
                low = MemoryUtil.memAlloc(material ? colorBytes * MATERIAL_PLANES : colorBytes).order(ByteOrder.LITTLE_ENDIAN);
                if (!material) high = MemoryUtil.memAlloc(colorBytes).order(ByteOrder.LITTLE_ENDIAN);
                depth = MemoryUtil.memAlloc(DEPTH_BYTES).order(ByteOrder.LITTLE_ENDIAN);
                return new Image(cameraX, cameraY, cameraZ, reverseDepth, colorFormat, low, high, depth);
            } catch (RuntimeException | Error failure) {
                if (low != null) MemoryUtil.memFree(low);
                if (high != null) MemoryUtil.memFree(high);
                if (depth != null) MemoryUtil.memFree(depth);
                throw failure;
            }
        }

        public double cameraX() { return cameraX; }
        public double cameraY() { return cameraY; }
        public double cameraZ() { return cameraZ; }
        public boolean reverseDepth() { return reverseDepth; }
        public ColorFormat colorFormat() { return colorFormat; }
        public ByteBuffer low() { return low.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN); }
        public ByteBuffer high() { return mutableHigh().asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN); }
        public ByteBuffer depth() { return depth.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN); }
        public ByteBuffer mutableLow() { return low.duplicate().order(ByteOrder.LITTLE_ENDIAN); }
        public ByteBuffer mutableHigh() {
            return high == null ? ByteBuffer.allocate(0) : high.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        }
        public ByteBuffer mutableDepth() { return depth.duplicate().order(ByteOrder.LITTLE_ENDIAN); }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            MemoryUtil.memFree(low);
            if (high != null) MemoryUtil.memFree(high);
            MemoryUtil.memFree(depth);
        }
    }

    private final Path directory;
    private final Executor io;
    private final long diskLimit;
    private static final ConcurrentHashMap<Path, SlotWrites> WRITES = new ConcurrentHashMap<>();
    private final AtomicBoolean pruning = new AtomicBoolean();
    private final AtomicBoolean pruneScheduled = new AtomicBoolean();
    private final AtomicLong lastPrune = new AtomicLong(Long.MIN_VALUE);
    private static final class SlotWrites { long sequence; int pending; }

    public ProjectedTileStore(Path directory, Executor io) {
        this(directory, io, DEFAULT_LIMIT);
    }

    public ProjectedTileStore(Path directory, Executor io, long diskLimit) {
        if (diskLimit < MAX_FILE_BYTES) throw new IllegalArgumentException("Projected tile disk limit is too small");
        this.directory = directory.toAbsolutePath().normalize().resolve("projected-v1");
        this.io = java.util.Objects.requireNonNull(io);
        this.diskLimit = diskLimit;
    }

    public CompletableFuture<Optional<Image>> read(String slot, String content) {
        validate(content);
        return readAsync(slot, () -> content);
    }

    public CompletableFuture<Optional<Image>> read(String slot, ProjectedTileIndex.DiskKey content) {
        return readAsync(slot, content::fingerprint);
    }

    private CompletableFuture<Optional<Image>> readAsync(String slot, Supplier<String> content) {
        validate(slot);
        var result = new CompletableFuture<Optional<Image>>();
        io.execute(() -> {
            if (result.isCancelled()) return;
            try {
                String fingerprint = content.get();
                validate(fingerprint);
                if (result.isCancelled()) return;
                var image = readFile(slot, fingerprint);
                if (!result.complete(image)) image.ifPresent(Image::close);
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            } catch (Error failure) {
                result.completeExceptionally(failure);
                throw failure;
            }
        });
        return result;
    }

    /** Reserves the slot before asynchronous GPU work so older bakes cannot win a race. */
    public WriteTicket reserve(String slot) {
        validate(slot);
        long[] sequence = new long[1];
        SlotWrites state = WRITES.compute(file(slot), (ignored, current) -> {
            if (current == null) current = new SlotWrites();
            synchronized (current) { sequence[0] = ++current.sequence; current.pending++; }
            return current;
        });
        return new WriteTicket(this, slot, state, sequence[0]);
    }

    public static final class WriteTicket implements AutoCloseable {
        private final ProjectedTileStore owner;
        private final String slot;
        private final SlotWrites state;
        private final long sequence;
        private final AtomicBoolean claimed = new AtomicBoolean();

        private WriteTicket(ProjectedTileStore owner, String slot, SlotWrites state, long sequence) {
            this.owner = owner; this.slot = slot; this.state = state; this.sequence = sequence;
        }

        @Override public void close() {
            if (claimed.compareAndSet(false, true)) owner.release(slot, state);
        }
    }

    /** The caller retains the image until the non-cancellable write future completes. */
    public CompletableFuture<Void> write(WriteTicket ticket, String content, Image image) {
        java.util.Objects.requireNonNull(ticket);
        validate(content);
        java.util.Objects.requireNonNull(image);
        if (ticket.owner != this || !ticket.claimed.compareAndSet(false, true))
            throw new IllegalArgumentException("Projected tile write ticket is not available");
        var result = new WriteFuture();
        try {
            io.execute(() -> {
                try {
                    writeFile(ticket.slot, content, image, ticket.state, ticket.sequence);
                    result.complete(null);
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                } catch (Error failure) {
                    result.completeExceptionally(failure);
                    throw failure;
                }
                finally { release(ticket.slot, ticket.state); }
            });
            return result;
        } catch (RuntimeException | Error rejected) {
            release(ticket.slot, ticket.state);
            throw rejected;
        }
    }

    private static final class WriteFuture extends CompletableFuture<Void> {
        @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
    }

    private Optional<Image> readFile(String slot, String content) {
        requireWorker();
        Path file = file(slot);
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            long size = Files.size(file);
            if (size < 69 || size > MAX_FILE_BYTES) return Optional.empty();
            try (var raw = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                if (raw.readInt() != MAGIC) return Optional.empty();
                int version = raw.readInt();
                if (version < 1 || version > VERSION) return Optional.empty();
                byte[] storedKey = new byte[32];
                raw.readFully(storedKey);
                if (!MessageDigest.isEqual(storedKey, HexFormat.of().parseHex(content))) return Optional.empty();
                double cameraX = raw.readDouble(), cameraY = raw.readDouble(), cameraZ = raw.readDouble();
                int depthFlag = raw.readUnsignedByte();
                if (depthFlag > 1) return Optional.empty();
                boolean reverseDepth = depthFlag != 0;
                int colorFlag = version == 1 ? 0 : raw.readUnsignedByte();
                if (colorFlag > (version >= 3 ? 2 : 1)) return Optional.empty();
                var colorFormat = Image.ColorFormat.values()[colorFlag];
                if (!Double.isFinite(cameraX) || !Double.isFinite(cameraY) || !Double.isFinite(cameraZ)) return Optional.empty();
                int checksum = raw.readInt();
                try (var data = new DataInputStream(new InflaterInputStream(raw))) {
                    Image image = Image.allocate(cameraX, cameraY, cameraZ, reverseDepth, colorFormat);
                    boolean transferred = false;
                    try {
                        byte[] buffer = new byte[32768];
                        readFully(data, image.mutableLow(), buffer);
                        readFully(data, image.mutableHigh(), buffer);
                        readFully(data, image.mutableDepth(), buffer);
                        if (data.read() != -1 || checksum != checksum(image, version >= 2)) return Optional.empty();
                        touch(file);
                        transferred = true;
                        return Optional.of(image);
                    } finally { if (!transferred) image.close(); }
                }
            }
        } catch (IOException | SecurityException failure) {
            LogUtils.getLogger().debug("Projected tile cache read failed for {}: {}", slot, failure.toString());
            return Optional.empty();
        }
    }

    private void writeFile(String slot, String content, Image image, SlotWrites state, long sequence) {
        requireWorker();
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, ".projected-tile-", ".tmp");
            try (var raw = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                raw.writeInt(MAGIC); raw.writeInt(VERSION);
                raw.write(HexFormat.of().parseHex(content));
                raw.writeDouble(image.cameraX()); raw.writeDouble(image.cameraY()); raw.writeDouble(image.cameraZ());
                raw.writeBoolean(image.reverseDepth());
                raw.writeByte(colorFlag(image));
                raw.writeInt(checksum(image, true));
                try (var data = new DeflaterOutputStream(raw)) {
                    byte[] buffer = new byte[32768];
                    writeFully(data, image.low(), buffer);
                    writeFully(data, image.high(), buffer);
                    writeFully(data, image.depth(), buffer);
                }
            }
            synchronized (state) {
                if (sequence == state.sequence) {
                    Files.move(temporary, file(slot), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    temporary = null;
                }
            }
            pruneIfDue();
        } catch (IOException failure) {
            throw new UncheckedIOException("Projected tile cache write failed for " + slot, failure);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); }
            catch (IOException | SecurityException failure) {
                LogUtils.getLogger().debug("Projected tile cache temporary cleanup failed: {}", failure.toString());
            }
        }
    }

    private void release(String slot, SlotWrites state) {
        WRITES.compute(file(slot), (ignored, current) -> {
            synchronized (state) {
                state.pending--;
                return current == state && state.pending == 0 ? null : current;
            }
        });
    }

    private record DiskEntry(Path path, long bytes, long modified, String slot) { }

    private void pruneIfDue() {
        long now = System.nanoTime();
        long previous = lastPrune.get();
        if (previous != Long.MIN_VALUE && now - previous < PRUNE_INTERVAL_NANOS) {
            schedulePrune(PRUNE_INTERVAL_NANOS - (now - previous));
            return;
        }
        if (!pruning.compareAndSet(false, true)) { schedulePrune(PRUNE_INTERVAL_NANOS); return; }
        try {
            previous = lastPrune.get();
            if (previous != Long.MIN_VALUE && now - previous < PRUNE_INTERVAL_NANOS) {
                schedulePrune(PRUNE_INTERVAL_NANOS - (now - previous));
                return;
            }
            lastPrune.set(now);
            var entries = new ArrayList<DiskEntry>();
            byte[] header = new byte[8];
            try (var paths = Files.list(directory)) {
                var iterator = paths.iterator();
                while (iterator.hasNext()) {
                    var path = iterator.next();
                    String name = path.getFileName().toString();
                    if (!name.endsWith(".image")) continue;
                    String slot = name.substring(0, name.length() - 6);
                    if (!KEY.matcher(slot).matches()) continue;
                    BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!attributes.isRegularFile() || attributes.size() < 8) continue;
                    try (var input = Files.newInputStream(path)) {
                        int read = 0;
                        while (read < 8) {
                            int n = input.read(header, read, 8 - read);
                            if (n < 0) break;
                            read += n;
                        }
                        if (read < 8) continue;
                        int magic = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16)
                                | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
                        int version = ((header[4] & 0xFF) << 24) | ((header[5] & 0xFF) << 16)
                                | ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
                        if (magic != MAGIC || version < 1 || version > VERSION) continue;
                    }
                    entries.add(new DiskEntry(path, attributes.size(), attributes.lastModifiedTime().toMillis(), slot));
                }
            }
            long bytes = entries.stream().mapToLong(DiskEntry::bytes).sum();
            if (bytes <= diskLimit) return;
            entries.sort(Comparator.comparingLong(DiskEntry::modified));
            for (DiskEntry entry : entries) {
                if (bytes <= diskLimit) break;
                boolean[] removed = new boolean[1];
                WRITES.compute(entry.path(), (ignored, current) -> {
                    if (current != null) return current;
                    try {
                        long modified = Files.getLastModifiedTime(entry.path(), LinkOption.NOFOLLOW_LINKS).toMillis();
                        if (modified == entry.modified()) removed[0] = Files.deleteIfExists(entry.path());
                    }
                    catch (IOException | SecurityException failure) {
                        LogUtils.getLogger().debug("Projected tile cache eviction failed for {}: {}", entry.slot(), failure.toString());
                    }
                    return null;
                });
                if (removed[0]) bytes -= entry.bytes();
            }
            if (bytes > diskLimit) schedulePrune(PRUNE_INTERVAL_NANOS);
        } catch (IOException | SecurityException failure) {
            LogUtils.getLogger().debug("Projected tile cache size scan failed: {}", failure.toString());
        } finally { pruning.set(false); }
    }

    private void schedulePrune(long delay) {
        if (!(io instanceof ScheduledExecutorService scheduled) || !pruneScheduled.compareAndSet(false, true)) return;
        try {
            scheduled.schedule(() -> {
                pruneScheduled.set(false);
                pruneIfDue();
            }, Math.max(1, delay), TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            pruneScheduled.set(false);
            LogUtils.getLogger().debug("Projected tile eviction could not be scheduled: {}", rejected.toString());
        }
    }

    private static void touch(Path file) {
        try {
            var attributes = Files.getFileAttributeView(file, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            long now = System.currentTimeMillis();
            if (now - attributes.readAttributes().lastModifiedTime().toMillis() >= TOUCH_INTERVAL_MILLIS)
                attributes.setTimes(FileTime.fromMillis(now), null, null);
        } catch (IOException | SecurityException failure) {
            LogUtils.getLogger().debug("Projected tile cache access time update failed: {}", failure.toString());
        }
    }

    private Path file(String key) { return directory.resolve(key + ".image"); }
    private static void validate(String key) {
        if (key == null || !KEY.matcher(key).matches()) throw new IllegalArgumentException("Invalid projected tile key");
    }
    private static void requireWorker() {
        if (Minecraft.getInstance().isSameThread())
            throw new IllegalStateException("Projected tile storage must run off the client thread");
    }
    private static int checksum(Image image, boolean includeFormat) {
        var crc = new CRC32();
        crc.update(ByteBuffer.allocate(25).putDouble(image.cameraX()).putDouble(image.cameraY()).putDouble(image.cameraZ())
                .put((byte) (image.reverseDepth() ? 1 : 0)).array());
        if (includeFormat) crc.update(colorFlag(image));
        crc.update(image.low()); crc.update(image.high()); crc.update(image.depth());
        return (int) crc.getValue();
    }

    private static int colorFlag(Image image) { return image.colorFormat().ordinal(); }

    private static void readFully(DataInputStream input, ByteBuffer output, byte[] buffer) throws IOException {
        while (output.hasRemaining()) {
            int length = Math.min(output.remaining(), buffer.length);
            input.readFully(buffer, 0, length);
            output.put(buffer, 0, length);
        }
    }

    private static void writeFully(DeflaterOutputStream output, ByteBuffer input, byte[] buffer) throws IOException {
        while (input.hasRemaining()) {
            int length = Math.min(input.remaining(), buffer.length);
            input.get(buffer, 0, length);
            output.write(buffer, 0, length);
        }
    }
}
