/**
     * Downloads a single chunk and writes it to the aggregator buffer.
     * Implements robust error handling and data validation.
     * 
     * Key Features:
     *  - Validates server response ranges
     *  - Handles packet timeouts with retries
     *  - Tracks connection-specific statistics
     *  - Prevents duplicate data writing
     *  - Enforces maximum packet size limit
     * 
     * After each chunk completion, displays:
     *  - Elapsed time
     *  - Current packet loss rate
     *  - RTT metrics (current and average)
     *  - Download speed
     *  - Progress percentage
     * 
     * Error Handling:
     *  - Socket timeouts (with configurable retry limit)
     *  - Invalid ranges from server
     *  - Data size mismatches
     *  - Buffer overflow protection
     * 
     * @param ip Server IP address
     * @param port Server port number
     * @param fileId ID of file to download
     * @param chunkStart Start byte position (0-based)
     * @param chunkEnd End byte position (0-based)
     * @param socket UDP socket to use
     * @param stats Statistics collector for this connection
     * @throws Exception if unrecoverable error occurs
     */
    package client;

    import com.sun.management.OperatingSystemMXBean;
    import model.FileDataResponseType;
    import model.FileDescriptor;
    import model.FileListResponseType;
    import model.FileSizeResponseType;
    import model.RequestType;
    import model.ResponseType;
    
    import java.io.FileInputStream;
    import java.io.FileOutputStream;
    import java.io.IOException;
    import java.lang.management.ManagementFactory;
    import java.net.*;
    import java.security.MessageDigest;
    import java.util.*;
    import java.util.concurrent.*;
    import java.util.concurrent.atomic.AtomicLong;
    
    /**
     * A UDP-based file transfer client implementing dynamic chunk scheduling with
     * aggregator pattern.
     * Uses two concurrent connections to download files in parallel for improved
     * performance.
     * 
     * Key Features:
     * - Dynamic chunk scheduling between two connections
     * - Real-time progress monitoring and statistics
     * - Robust error handling and retry mechanisms
     * - Comprehensive download summary with detailed metrics
     * - MD5 checksum verification
     * - Memory-efficient aggregator pattern for file assembly
     * 
     * Implementation Details:
     * - Uses UDP for data transfer with timeout handling
     * - Maximum packet size is limited to 1000 bytes
     * - Server responses are validated for range correctness
     * - Maintains separate statistics for each connection
     * - Implements thread-safe concurrent operations
     * - Provides detailed error logging and performance metrics
     */
    public class soClient {
    
        // Default chunk size for file downloads - 256 KB
        private static final int CHUNK_SIZE = 256 * 1024;
    
        // UDP sockets for two connections
        private DatagramSocket socket1;
        private DatagramSocket socket2;
    
        // Per-connection statistics
        private ConnectionStats stats1;
        private ConnectionStats stats2;
    
        // Map to store ID->FileName information from server
        private Map<Integer, String> fileListMap = new HashMap<>();
    
        // RAM array to store downloaded file
        private byte[] aggregator;
    
        // Total bytes downloaded counter
        private AtomicLong totalBytesDownloaded = new AtomicLong(0);
    
        // Target file size to be downloaded
        private long totalFileSize = 0;
    
        // Download start timestamp
        private long startDownloadTime;
    
        // Total bytes downloaded by each connection
        private AtomicLong bytesDownloadedByConn1 = new AtomicLong(0);
        private AtomicLong bytesDownloadedByConn2 = new AtomicLong(0);
    
        /**
         * Thread-safe statistics collector for a single connection.
         * Tracks various metrics to monitor connection performance and reliability.
         * 
         * Tracked Metrics:
         * - Packets sent and received
         * - Timeout counts
         * - RTT measurements (total, count, average, last)
         * - Invalid range responses
         * 
         * Features:
         * - All methods are synchronized for thread safety
         * - Separate tracking for initial and subsequent packet receives
         * - Automatic RTT calculation for first packet of each chunk
         * - Loss rate calculation
         * - Rolling RTT average
         * 
         * Usage:
         * - Created for each connection at client initialization
         * - Updated during chunk downloads
         * - Used for progress monitoring and final summary
         * - Helps identify connection-specific issues
         */
        private static class ConnectionStats {
            public final String name; // "Connection-1" / "Connection-2"
            public long packetsSent = 0;
            public long packetsReceived = 0;
            public long timeouts = 0; // Socket timeout counter
            public long totalRTT = 0; // Cumulative sum of RTTs
            public long countRTT = 0; // Number of packets with RTT measured
            public long lastRTT = 0; // Most recent RTT value (ms)
            public long invalidRangeCount = 0; // Counter for invalid range responses
            public long largePacketCount = 0;
            public long dataMismatchCount = 0;
            private long lastChunkSize = 0; // ms
            private long lastChunkTime = 0; // ms
    
            public ConnectionStats(String name) {
                this.name = name;
            }
    
            public synchronized void addSent() {
                packetsSent++;
            }
    
            public synchronized void addReceived(long rttMs) {
                packetsReceived++;
                totalRTT += rttMs;
                lastRTT = rttMs;
                countRTT++;
            }
    
            public synchronized void addSubsequentReceive() {
                packetsReceived++;
            }
    
            public synchronized void addTimeout() {
                timeouts++;
            }
    
            public synchronized void addInvalidRange() {
                invalidRangeCount++;
            }
    
            public synchronized double getAverageRTT() {
                if (countRTT == 0)
                    return 0.0;
                return (double) totalRTT / (double) countRTT;
            }
    
            public synchronized double getLossRate() {
                if (packetsSent == 0)
                    return 0.0;
                return (double) timeouts / (double) packetsSent;
            }
    
            public synchronized void addLargePacket() {
                largePacketCount++;
            }
    
            public synchronized void addDataMismatch() {
                dataMismatchCount++;
            }
    
            public synchronized void setLastTransfer(long bytes, long timeMs) {
                this.lastChunkSize = bytes;
                this.lastChunkTime = timeMs;
            }
    
            public synchronized double getCurrentSpeed() { // Mbps cinsinden
                if (lastChunkTime == 0)
                    return 0.0;
                return (lastChunkSize * 8.0) / (lastChunkTime * 1000.0); // Mbps
            }
        }
    
        /**
         * Constructor: Initializes UDP sockets and connection statistics
         */
        public soClient() throws SocketException {
            this.socket1 = new DatagramSocket();
            this.socket2 = new DatagramSocket();
            this.stats1 = new ConnectionStats("Connection-1");
            this.stats2 = new ConnectionStats("Connection-2");
        }
    
        /**
         * Retrieves file list from server and displays it, also saves to fileListMap.
         * Uses GET_FILE_LIST request type to get available files.
         * Implements timeout and error handling.
         */
        public void getFileList(String ip, int port) throws IOException {
            try {
                InetAddress IPAddress = InetAddress.getByName(ip);
    
                // GET_FILE_LIST request
                RequestType req = new RequestType(
                        RequestType.REQUEST_TYPES.GET_FILE_LIST,
                        0,
                        0,
                        0,
                        null);
                byte[] sendData = req.toByteArray();
                DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, port);
                socket1.send(sendPacket);
    
                // Set timeout for receive
                socket1.setSoTimeout(2000); // 2 second timeout
    
                // Get response
                byte[] receiveData = new byte[ResponseType.MAX_RESPONSE_SIZE];
                DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                socket1.receive(receivePacket);
    
                FileListResponseType response = new FileListResponseType(receivePacket.getData());
                fileListMap.clear();
    
                System.out.println("\n--- File List from Server ---");
                for (FileDescriptor fd : response.getFileDescriptors()) {
                    System.out.println(fd.getFile_id() + ". " + fd.getFile_name());
                    fileListMap.put(fd.getFile_id(), fd.getFile_name());
                }
                System.out.println("-----------------------------\n");
            } catch (SocketTimeoutException e) {
                System.err.println("Server timeout while getting file list. Retrying...");
                throw new IOException("Server timeout");
            } catch (Exception e) {
                System.err.println("Error while getting file list: " + e.getMessage());
                throw new IOException("File list error: " + e.getMessage());
            }
        }
    
        /**
         * Gets file size from server using GET_FILE_SIZE request.
         * Returns -1 if request fails.
         */
        public long getFileSize(String ip, int port, int fileId) throws IOException {
            InetAddress IPAddress = InetAddress.getByName(ip);
    
            RequestType req = new RequestType(
                    RequestType.REQUEST_TYPES.GET_FILE_SIZE,
                    fileId,
                    0,
                    0,
                    null);
            byte[] sendData = req.toByteArray();
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, port);
            socket1.send(sendPacket);
    
            byte[] receiveData = new byte[ResponseType.MAX_RESPONSE_SIZE];
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            socket1.receive(receivePacket);
    
            FileSizeResponseType response = new FileSizeResponseType(receivePacket.getData());
            if (response.getResponseType() == ResponseType.RESPONSE_TYPES.GET_FILE_SIZE_SUCCESS) {
                return response.getFileSize();
            } else {
                System.err.println("GET_FILE_SIZE failed, response: " + response.getResponseType());
                return -1;
            }
        }
    
        /**
         * Downloads a single chunk and copies it to aggregator.
         * Displays requested metrics after each chunk completion:
         * - Elapsed time
         * - Packet loss rate
         * - Current and average RTT
         * - Average download speed
         */
        private void aggregatorDownloadChunk(String ip,
                int port,
                int fileId,
                long chunkStart,
                long chunkEnd,
                DatagramSocket socket,
                ConnectionStats stats) throws Exception {
            long serverStart = chunkStart + 1; // server uses 1-based indexing
            long serverEnd = chunkEnd + 1;
            long totalBytesNeeded = (chunkEnd - chunkStart + 1);
            long startTime = System.currentTimeMillis();
    
            if (totalBytesNeeded <= 0) {
                return;
            }
    
            // Track which bytes have been written to avoid duplicates
            boolean[] written = new boolean[(int) totalBytesNeeded];
            long totalReceived = 0;
    
            // GET_FILE_DATA request setup
            InetAddress ipAddress = InetAddress.getByName(ip);
            RequestType req = new RequestType(
                    RequestType.REQUEST_TYPES.GET_FILE_DATA,
                    fileId,
                    serverStart,
                    serverEnd,
                    null);
            byte[] sendData = req.toByteArray();
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, ipAddress, port);
    
            // Set socket timeout and retry limit
            socket.setSoTimeout(2000);
            int emptyCount = 0;
            final int MAX_EMPTY = 30;
    
            stats.addSent();
            long requestSendTime = System.nanoTime();
            socket.send(sendPacket);
    
            while (true) {
                if (totalReceived >= totalBytesNeeded) {
                    break;
                }
                try {
                    byte[] buf = new byte[ResponseType.MAX_RESPONSE_SIZE];
                    DatagramPacket receivePacket = new DatagramPacket(buf, buf.length);
                    socket.receive(receivePacket);
    
                    // Calculate RTT for first packet
                    if (totalReceived == 0) {
                        long rttMs = (System.nanoTime() - requestSendTime) / 1_000_000;
                        stats.addReceived(rttMs);
                    } else {
                        stats.addSubsequentReceive();
                    }
    
                    FileDataResponseType response = new FileDataResponseType(receivePacket.getData());
                    if (response.getResponseType() != ResponseType.RESPONSE_TYPES.GET_FILE_DATA_SUCCESS) {
                        System.err.println("Chunk error: response=" + response.getResponseType());
                        break;
                    }
    
                    long chunkStartServer = response.getStart_byte() - 1;
                    long chunkEndServer = response.getEnd_byte() - 1;
                    byte[] data = response.getData();
    
                    // Validate server response range
                    if (chunkStartServer < chunkStart || chunkEndServer > chunkEnd) {
                        System.err.println("Server returned invalid range: " +
                                chunkStartServer + "-" + chunkEndServer +
                                " (requested: " + chunkStart + "-" + chunkEnd + ")");
                        stats.addInvalidRange();
                        continue;
                    }
    
                    // Check data size
                    if (data.length > 1000) {
                        System.err.println("Server returned too large packet: " + data.length + " bytes");
                        stats.addLargePacket(); // Bu satırı ekle
                        continue;
                    }
    
                    // Verify data length matches the declared range
                    if (data.length != (chunkEndServer - chunkStartServer + 1)) {
                        System.err.println("Data length mismatch: got " + data.length +
                                " bytes but range is " + chunkStartServer + "-" + chunkEndServer);
                        stats.addDataMismatch(); // Bu satırı ekle
                        continue;
                    }
    
                    long offset = chunkStartServer - chunkStart;
                    if (offset < 0)
                        continue;
                    if (offset + data.length > totalBytesNeeded)
                        continue;
    
                    // Write to aggregator, avoiding duplicates
                    for (int i = 0; i < data.length; i++) {
                        int idx = (int) (offset + i);
                        if (idx >= written.length) {
                            System.err.println("Index out of bounds: " + idx + " >= " + written.length);
                            continue;
                        }
                        if (!written[idx]) {
                            aggregator[(int) (chunkStart + idx)] = data[i];
                            written[idx] = true;
                            totalReceived++;
                        }
                    }
                    emptyCount = 0;
    
                } catch (SocketTimeoutException e) {
                    System.err.println("Timeout chunk(" + chunkStart + "-" + chunkEnd + "), re-sending request...");
                    stats.addTimeout();
                    socket.send(sendPacket);
                    stats.addSent();
                    requestSendTime = System.nanoTime();
                    emptyCount++;
                    if (emptyCount > MAX_EMPTY) {
                        System.err.println("Max empty reached for chunk: " + chunkStart + "-" + chunkEnd);
                        break;
                    }
                }
            }
    
            // Update per-connection byte counters
            if (socket == socket1) {
                bytesDownloadedByConn1.addAndGet(totalReceived);
            } else {
                bytesDownloadedByConn2.addAndGet(totalReceived);
            }
    
            // Update global total
            long newGlobal = totalBytesDownloaded.addAndGet(totalReceived);
    
            // Calculate and display metrics after chunk completion
            long elapsedMs = System.currentTimeMillis() - startDownloadTime;
            double elapsedSec = elapsedMs / 1000.0;
            double lossRateSoFar = stats.getLossRate() * 100.0;
            double avgRTT = stats.getAverageRTT();
            long currentRTT = stats.lastRTT;
    
            double percentSoFar = 100.0 * newGlobal / totalFileSize;
    
            // Average speed = (total bytes * 8) / (elapsed seconds * 1024 * 1024) for Mbps
            double avgSpeedMbps = (newGlobal * 8.0) / (elapsedSec * 1024.0 * 1024.0);
    
            long endTime = System.currentTimeMillis();
            stats.setLastTransfer(totalReceived, endTime - startTime);
    
            System.out.printf(
                    "Chunk done [%d-%d], %s => +%,d bytes, total=%,d/%d (%.2f%%)\n" +
                            "  Elapsed: %.2fs, LossRate: %.2f%%, CurrentRTT: %dms, AvgRTT: %.2fms, AvgSpeed: %.2f Mbps\n",
                    chunkStart, chunkEnd, stats.name, totalReceived,
                    newGlobal, totalFileSize, percentSoFar,
                    elapsedSec, lossRateSoFar, currentRTT, avgRTT, avgSpeedMbps);
    
            System.out.printf(" Final Speed          : %.2f Mbps\n", stats1.getCurrentSpeed());
            System.out.printf(" Final Speed          : %.2f Mbps\n", stats2.getCurrentSpeed());
        }
    
        /**
         * Represents a file chunk to be downloaded.
         * Each chunk is defined by its start and end byte positions.
         * Used for distributing download tasks between worker threads.
         * 
         * Note: byte positions are 0-based internally but converted to 1-based
         * when communicating with server.
         * 
         * Implementation:
         * - Immutable data structure
         * - Used in conjunction with BlockingQueue for thread-safe task distribution
         * - Each chunk is sized according to CHUNK_SIZE constant (default 256KB)
         * - Last chunk may be smaller than CHUNK_SIZE
         */
        static class ChunkTask implements Comparable<ChunkTask> {
            long start;
            long end;
            long size;
            
            public ChunkTask(long start, long end) {
                this.start = start;
                this.end = end;
                this.size = end - start + 1;
            }
            
            @Override
            public int compareTo(ChunkTask other) {
                return Long.compare(this.size, other.size);  // Boyuta göre sıralama
            }
        }
    
        /**
         * A private class responsible for distributing file chunks between two
         * connections
         * based on their relative speeds and performance.
         */
        private class ChunkDistributor {
            // Queue to store and prioritize chunks waiting to be distributed
            private final PriorityBlockingQueue<ChunkTask> queue;
    
            // Connection statistics for the first connection
            private final ConnectionStats stats1;
    
            // Connection statistics for the second connection
            private final ConnectionStats stats2;
    
            // Lock object for thread synchronization
            private final Object lock = new Object();
    
            // Flag to indicate if the distributor is currently active
            //private volatile boolean isActive = true;
    
            /**
             * Constructor initializes the distributor with chunks and connection statistics
             * 
             * @param chunks List of chunks to be distributed
             * @param stats1 Statistics for first connection
             * @param stats2 Statistics for second connection
             */
            public ChunkDistributor(List<ChunkTask> chunks, ConnectionStats stats1, ConnectionStats stats2) {
                this.queue = new PriorityBlockingQueue<>(chunks);
                this.stats1 = stats1;
                this.stats2 = stats2;
            }
    
            /**
             * Retrieves the next chunk for the requesting connection based on speed ratios
             * 
             * @param requesterStats Statistics of the requesting connection
             * @return ChunkTask or null if queue is empty
             */
            public ChunkTask getNextChunk(ConnectionStats requesterStats) {
                synchronized (lock) {
                    // Return null if there are no chunks available
                    if (queue.isEmpty())
                        return null;
    
                    // Get current speeds for both connections
                    double speed1 = stats1.getCurrentSpeed();
                    double speed2 = stats2.getCurrentSpeed();
    
                    // Calculate chunk size based on total speed of both connections
                    double totalSpeed = speed1 + speed2;
                    // Default ratio assumes equal distribution
                    double ratio = 0.5;
    
                    // Calculate the appropriate ratio based on connection speeds
                    if (totalSpeed > 0) {
                        if (requesterStats == stats1) {
                            ratio = speed1 / totalSpeed; // Ratio for first connection
                        } else {
                            ratio = speed2 / totalSpeed; // Ratio for second connection
                        }
                    }
    
                    // Retrieve next chunk from the priority queue
                    ChunkTask chunk = queue.poll();
                    if (chunk != null) {
                        // Adjust chunk size based on the calculated speed ratio
                        long newSize = (long) (chunk.size * ratio);
                        // Ensure chunk size doesn't fall below minimum threshold
                        if (newSize < CHUNK_SIZE / 10)
                            newSize = CHUNK_SIZE / 10;
    
                        // Create and return a new chunk task with adjusted size
                        return new ChunkTask(chunk.start, chunk.start + newSize - 1);
                    }
                    return null;
                }
            }
        }
    
        /**
         * Worker thread class that pulls chunks from queue and downloads them.
         * Continues until queue is empty.
         */
        class DownloaderThread implements Runnable {
            private String ip;
            private int port;
            private int fileId;
            private DatagramSocket socket;
            private ChunkDistributor distributor;
            private ConnectionStats stats;
    
            public DownloaderThread(String ip,
                    int port,
                    int fileId,
                    DatagramSocket socket,
                    ChunkDistributor distributor, // BlockingQueue<ChunkTask> queue yerine
                    ConnectionStats stats) {
                this.ip = ip;
                this.port = port;
                this.fileId = fileId;
                this.socket = socket;
                this.distributor = distributor; // queue yerine distributor
                this.stats = stats;
            }
    
            @Override
            public void run() {
                while (true) {
                    ChunkTask task = distributor.getNextChunk(stats);
                    if (task == null)
                        break;
    
                    long startTime = System.currentTimeMillis();
                    try {
                        aggregatorDownloadChunk(ip, port, fileId, task.start, task.end, socket, stats);
                        long endTime = System.currentTimeMillis();
                        stats.setLastTransfer(task.size, endTime - startTime);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    
        /**
         * Main download method:
         * - Splits file into CHUNK_SIZE chunks
         * - Downloads using 2 threads
         * - Writes to disk
         * - Shows download summary
         * Returns true if download was successful
         */
        public boolean downloadFile(String ip1,
                int port1,
                String ip2,
                int port2,
                int fileId,
                long fileSize,
                String fileName) {
            if (fileSize <= 0) {
                System.err.println("Invalid file size!");
                return false;
            }
    
            this.totalFileSize = fileSize;
            this.aggregator = new byte[(int) fileSize];
            this.totalBytesDownloaded.set(0);
            bytesDownloadedByConn1.set(0);
            bytesDownloadedByConn2.set(0);
    
            // Create chunk list
            List<ChunkTask> chunkList = new ArrayList<>();
            for (long start = 0; start < fileSize; start += CHUNK_SIZE) {
                long end = Math.min(start + CHUNK_SIZE - 1, fileSize - 1);
                chunkList.add(new ChunkTask(start, end));
            }
            System.out.println("Total chunks: " + chunkList.size());
    
            // Reset connection statistics
            resetStats(stats1);
            resetStats(stats2);
    
            // BlockingQueue<ChunkTask> queue = new LinkedBlockingQueue<>(chunkList); SİL
            // Oğulcan
    
            System.out.println("Starting download ...");
            ChunkDistributor distributor = new ChunkDistributor(chunkList, stats1, stats2);
            startDownloadTime = System.currentTimeMillis();
            DownloaderThread w1 = new DownloaderThread(ip1, port1, fileId, socket1, distributor, stats1);
            DownloaderThread w2 = new DownloaderThread(ip2, port2, fileId, socket2, distributor, stats2);
    
            ExecutorService exec = Executors.newFixedThreadPool(2);
            exec.execute(w1);
            exec.execute(w2);
            exec.shutdown();
    
            try {
                exec.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
                return false;
            }
    
            long endTime = System.currentTimeMillis();
            long totalElapsedMs = endTime - startDownloadTime;
    
            // Calculate MD5 first
            String md5Hash = "";
            try {
                md5Hash = calculateMD5(fileName);
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
    
            // Write aggregator to file
            try (FileOutputStream fos = new FileOutputStream(fileName)) {
                fos.write(aggregator);
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
    
            System.out.printf("File %s has been downloaded in %d ms. The MD5 hash is %s.\n",
                    fileName, totalElapsedMs, md5Hash);
    
            // Show summary statistics
            printSummary(fileName, totalElapsedMs);
            return true;
        }
    
        /**
         * Resets all statistics for a connection
         */
        private void resetStats(ConnectionStats s) {
            s.packetsSent = 0;
            s.packetsReceived = 0;
            s.timeouts = 0;
            s.totalRTT = 0;
            s.countRTT = 0;
            s.lastRTT = 0;
        }
    
        /**
         * Generates and displays comprehensive download summary.
         * Collects and formats all relevant statistics from both connections.
         * 
         * Summary Sections:
         * 1. File Information
         * - Name and size
         * - MD5 checksum
         * - Total download time
         * 
         * 2. Overall Performance
         * - Combined download speed (Mbps)
         * - Total packet statistics
         * - Average loss rate
         * - Combined RTT metrics
         * 
         * 3. System Resources
         * - CPU usage percentage
         * - Memory utilization
         * 
         * 4. Per-Connection Details
         * - Bytes downloaded and percentage
         * - Packet statistics
         * - Timeout counts
         * - Invalid range counts
         * - Loss rates
         * - RTT metrics
         * 
         * Note: Memory usage is reported in MB for better readability
         * 
         * @param fileName       Name of downloaded file
         * @param totalElapsedMs Total download time in milliseconds
         */
        private void printSummary(String fileName, long totalElapsedMs) {
            double totalElapsedSec = totalElapsedMs / 1000.0;
    
            // Get CPU & RAM info
            OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
            double cpuLoad = osBean.getProcessCpuLoad() * 100.0;
            long totalMem = osBean.getTotalMemorySize();
            long freeMem = osBean.getFreeMemorySize();
            long usedMem = totalMem - freeMem;
            long totalLargePackets = stats1.largePacketCount + stats2.largePacketCount;
            long totalDataMismatches = stats1.dataMismatchCount + stats2.dataMismatchCount;
    
            // Calculate packet statistics
            long totalPacketsSent = stats1.packetsSent + stats2.packetsSent;
            long totalPacketsReceived = stats1.packetsReceived + stats2.packetsReceived;
            long totalTimeouts = stats1.timeouts + stats2.timeouts;
    
            double totalLossRate = 0.0;
            if (totalPacketsSent > 0) {
                totalLossRate = (double) totalTimeouts / (double) totalPacketsSent;
            }
            double totalLossPct = totalLossRate * 100.0;
    
            double overallMbps = (totalFileSize * 8.0) / (totalElapsedSec * 1024.0 * 1024.0);
    
            // Calculate per-connection download percentages
            long c1Bytes = bytesDownloadedByConn1.get();
            long c2Bytes = bytesDownloadedByConn2.get();
            double c1Percent = 100.0 * c1Bytes / totalFileSize;
            double c2Percent = 100.0 * c2Bytes / totalFileSize;
    
            // Calculate average RTTs
            double avgRttConn1 = stats1.getAverageRTT();
            double avgRttConn2 = stats2.getAverageRTT();
            double combinedAvgRtt = (avgRttConn1 + avgRttConn2) / 2.0;
    
            // Calculate total invalid ranges
            long totalInvalidRanges = stats1.invalidRangeCount + stats2.invalidRangeCount;
    
            // Print all summary information
            System.out.println("\n=== Download Summary ===");
            System.out.printf("File name             : %s\n", fileName);
            System.out.printf("File size             : %,d bytes\n", totalFileSize);
            System.out.printf("Total elapsed time    : %.2f s\n", totalElapsedSec);
            System.out.printf("Overall speed         : %.2f Mbps\n", overallMbps);
            System.out.printf("Total packets sent     : %d\n", totalPacketsSent);
            System.out.printf("Total packets received : %d\n", totalPacketsReceived);
            System.out.printf("Total timeouts         : %d\n", totalTimeouts);
            System.out.printf("Total invalid ranges   : %d\n", totalInvalidRanges);
            System.out.printf("Total large packets    : %d\n", totalLargePackets);
            System.out.printf("Total data mismatches  : %d\n", totalDataMismatches);
            System.out.printf("Average loss rate      : %.2f %%\n", totalLossPct);
            System.out.printf("Combined avg. RTT      : %.2f ms\n", combinedAvgRtt);
            System.out.printf("CPU usage             : %.2f %%\n", cpuLoad);
            System.out.printf("RAM usage             : %.2f MB used / %.2f MB total\n",
                    usedMem / (1024.0 * 1024.0), totalMem / (1024.0 * 1024.0));
    
            System.out.printf("\n--- %s ---\n", stats1.name);
            System.out.printf(" Bytes downloaded     : %,d (%.2f%%)\n", c1Bytes, c1Percent);
            System.out.printf(" Packets Sent         : %d\n", stats1.packetsSent);
            System.out.printf(" Packets Received     : %d\n", stats1.packetsReceived);
            System.out.printf(" Timeouts             : %d\n", stats1.timeouts);
            System.out.printf(" Invalid ranges       : %d\n", stats1.invalidRangeCount);
            System.out.printf(" Large packets        : %d\n", stats1.largePacketCount);
            System.out.printf(" Data mismatches      : %d\n", stats1.dataMismatchCount);
            System.out.printf(" Loss Rate            : %.2f %%\n", 100.0 * stats1.getLossRate());
            System.out.printf(" Average RTT          : %.2f ms\n", avgRttConn1);
    
            System.out.printf("\n--- %s ---\n", stats2.name);
            System.out.printf(" Bytes downloaded     : %,d (%.2f%%)\n", c2Bytes, c2Percent);
            System.out.printf(" Packets Sent         : %d\n", stats2.packetsSent);
            System.out.printf(" Packets Received     : %d\n", stats2.packetsReceived);
            System.out.printf(" Timeouts             : %d\n", stats2.timeouts);
            System.out.printf(" Invalid ranges       : %d\n", stats2.invalidRangeCount);
            System.out.printf(" Large packets        : %d\n", stats2.largePacketCount);
            System.out.printf(" Data mismatches      : %d\n", stats2.dataMismatchCount);
            System.out.printf(" Loss Rate            : %.2f %%\n", 100.0 * stats2.getLossRate());
            System.out.printf(" Average RTT          : %.2f ms\n", avgRttConn2);
            System.out.println("======================================\n");
        }
    
        /**
         * Simple MD5 hash calculator for verifying file integrity
         */
        public String calculateMD5(String filePath) throws Exception {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (FileInputStream fis = new FileInputStream(filePath)) {
                byte[] buf = new byte[4096];
                int bytesRead;
                while ((bytesRead = fis.read(buf)) != -1) {
                    md.update(buf, 0, bytesRead);
                }
            }
            byte[] hashBytes = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
    
        /**
         * Main program entry point and control loop.
         * Handles user interaction and orchestrates the download process.
         * 
         * Program Flow:
         * 1. Command Line Validation
         * - Requires two server addresses
         * - Parses IP and port information
         * 
         * 2. Main Interactive Loop
         * - Fetches and displays file list
         * - Handles user input
         * - Validates file selection
         * - Manages download process
         * - Shows progress and results
         * 
         * Error Handling:
         * - Invalid command line arguments
         * - Network connection failures
         * - Invalid user input
         * - File list retrieval failures
         * - Download interruptions
         * 
         * Features:
         * - Automatic retry for file list retrieval
         * - Graceful error recovery
         * - Clear user feedback
         * - Clean program termination
         * 
         * @param args Command line arguments: <server_IP1>:<port1> <server_IP2>:<port2>
         * @throws Exception if unrecoverable error occurs
         */
        public static void main(String[] args) throws Exception {
            if (args.length < 2) {
                System.err.println("Usage: java soClient <server_IP1>:<port1> <server_IP2>:<port2>");
                return;
            }
    
            String[] arr1 = args[0].split(":");
            String ip1 = arr1[0];
            int port1 = Integer.parseInt(arr1[1]);
    
            String[] arr2 = args[1].split(":");
            String ip2 = arr2[0];
            int port2 = Integer.parseInt(arr2[1]);
    
            soClient client = new soClient();
    
            Scanner scanner = new Scanner(System.in);
    
            // Main program loop
            while (true) {
                int retryCount = 0;
                final int MAX_RETRIES = 3;
                boolean fileListSuccess = false;
    
                while (retryCount < MAX_RETRIES && !fileListSuccess) {
                    try {
                        // Get and show file list from server each time
                        client.getFileList(ip1, port1);
                        fileListSuccess = true;
                    } catch (IOException e) {
                        System.err.println("Attempt " + (retryCount + 1) + "/" + MAX_RETRIES +
                                " failed: " + e.getMessage());
                        retryCount++;
                        if (retryCount >= MAX_RETRIES) {
                            System.err.println("Could not get file list after " + MAX_RETRIES +
                                    " attempts. Please check server connection.");
                            continue;
                        }
                        try {
                            Thread.sleep(1000); // Wait 1 second before retry
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
    
                System.out.print("Enter a fileId to download (0 to exit): ");
                String input = scanner.nextLine().trim();
                if (input.equalsIgnoreCase("0")) {
                    System.out.println("Exiting program...");
                    break;
                }
    
                int fileId;
                try {
                    fileId = Integer.parseInt(input);
                } catch (NumberFormatException e) {
                    System.out.println("Invalid input. Please enter a valid fileId or 0.");
                    continue;
                }
    
                String fileName = client.fileListMap.get(fileId);
                if (fileName == null) {
                    System.err.println("No file found for fileId: " + fileId);
                    // Continue loop
                    continue;
                }
    
                // Get file size
                long fileSize = client.getFileSize(ip1, port1, fileId);
                if (fileSize <= 0) {
                    System.err.println("Could not get valid file size for fileId=" + fileId);
                    continue;
                }
    
                System.out.printf("**File %s has been selected. Getting the size information...**\n", fileName);
                System.out.printf("**File %s is %d bytes. Starting to download...**\n", fileName, fileSize);
    
                // Start download
                boolean success = client.downloadFile(ip1, port1, ip2, port2, fileId, fileSize, fileName);
                if (!success) {
                    System.err.println("Download failed or was interrupted. Let's continue.\n");
                } else {
                    System.out.println("Download completed. You can choose another file...\n");
                }
            }
    
            scanner.close();
            System.out.println("Program terminated.");
        }
    }
