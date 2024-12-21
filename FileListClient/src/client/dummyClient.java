package client;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.concurrent.*;
import java.util.zip.*;
import java.util.*;

public class dummyClient {
    // Existing constants
    private static final int BUFFER_SIZE = 1024;
    private static final int SOCKET_TIMEOUT = 1000;
    private static final int INITIAL_CHUNK_SIZE = 1000;

    // New constants for enhancements
    private static final int COMPRESSION_LEVEL = 6;
    private static final int MIN_CHUNK_SIZE = 500;
    private static final int MAX_CHUNK_SIZE = 2000;
    private static final int RTT_HISTORY_SIZE = 10;
    private static final double TIMEOUT_MULTIPLIER = 1.5;

    // Existing fields
    private DatagramSocket socket1;
    private DatagramSocket socket2;
    private InetAddress serverAddress1;
    private InetAddress serverAddress2;
    private int serverPort1;
    private int serverPort2;
    private long totalBytesDownloaded = 0;
    private Map<Integer, ConnectionStats> connectionStats = new HashMap<>();

    // New fields for enhancements
    private Map<Integer, Integer> dynamicChunkSizes = new HashMap<>();
    private Map<Integer, Queue<Long>> rttHistory = new HashMap<>();
    private Map<Integer, Integer> dynamicTimeouts = new HashMap<>();

    private static class ConnectionStats {
        long bytesTransferred = 0;
        int packetsLost = 0;
        long totalRTT = 0;
        int rttCount = 0;
        long lastThroughput = 0;
        long lastUpdateTime = System.currentTimeMillis();

        double getAverageRTT() {
            return rttCount > 0 ? (double) totalRTT / rttCount : 0;
        }

        void updateThroughput(long bytes) {
            long currentTime = System.currentTimeMillis();
            long timeDiff = currentTime - lastUpdateTime;
            if (timeDiff > 0) {
                lastThroughput = (bytes * 1000) / timeDiff; // bytes per second
            }
            lastUpdateTime = currentTime;
            bytesTransferred += bytes;
        }
    }

    // Constructor remains same but initialize new fields
    public dummyClient(String address1, int port1, String address2, int port2) throws IOException {
        // Existing initialization code...

        // Initialize new fields
        for (int connId : Arrays.asList(1, 2)) {
            dynamicChunkSizes.put(connId, INITIAL_CHUNK_SIZE);
            rttHistory.put(connId, new LinkedList<>());
            dynamicTimeouts.put(connId, SOCKET_TIMEOUT);
        }
    }

    // Compression methods
    private byte[] compressData(byte[] data) throws IOException {
        Deflater deflater = new Deflater();
        deflater.setLevel(COMPRESSION_LEVEL);
        deflater.setInput(data);
        deflater.finish();

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(data.length);
        byte[] buffer = new byte[BUFFER_SIZE];

        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            outputStream.write(buffer, 0, count);
        }

        outputStream.close();
        deflater.end();

        return outputStream.toByteArray();
    }

    private byte[] decompressData(byte[] compressedData) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(compressedData);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(compressedData.length);
        byte[] buffer = new byte[BUFFER_SIZE];

        while (!inflater.finished()) {
            try {
                int count = inflater.inflate(buffer);
                outputStream.write(buffer, 0, count);
            } catch (Exception e) {
                System.err.println("Decompression error: " + e.getMessage());
                break;
            }
        }

        outputStream.close();
        inflater.end();

        return outputStream.toByteArray();
    }

    // Enhanced chunk management
    private void adjustChunkSize(int connectionId, long rtt, int lossCount) {
        int currentChunkSize = dynamicChunkSizes.get(connectionId);

        // Increase chunk size if connection is performing well
        if (lossCount == 0 && rtt < getAverageRTT(connectionId)) {
            currentChunkSize = Math.min(currentChunkSize * 2, MAX_CHUNK_SIZE);
        }
        // Decrease chunk size if experiencing issues
        else if (lossCount > 0 || rtt > getAverageRTT(connectionId) * 1.5) {
            currentChunkSize = Math.max(currentChunkSize / 2, MIN_CHUNK_SIZE);
        }

        dynamicChunkSizes.put(connectionId, currentChunkSize);
    }

    private double getAverageRTT(int connectionId) {
        Queue<Long> rtts = rttHistory.get(connectionId);
        if (rtts.isEmpty())
            return SOCKET_TIMEOUT;

        return rtts.stream().mapToLong(Long::longValue).average().orElse(SOCKET_TIMEOUT);
    }

    private void updateRTTHistory(int connectionId, long rtt) {
        Queue<Long> rtts = rttHistory.get(connectionId);
        rtts.offer(rtt);
        while (rtts.size() > RTT_HISTORY_SIZE) {
            rtts.poll();
        }

        // Update dynamic timeout
        double avgRTT = getAverageRTT(connectionId);
        int newTimeout = (int) (avgRTT * TIMEOUT_MULTIPLIER);
        dynamicTimeouts.put(connectionId, newTimeout);
    }

    // Enhanced download method
    private void downloadFile(int fileId, long fileSize) throws Exception {
        long startTime = System.currentTimeMillis();
        ByteArrayOutputStream fileData = new ByteArrayOutputStream();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Create two priority queues for chunks
        ConcurrentLinkedQueue<Integer> frontChunks = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Integer> backChunks = new ConcurrentLinkedQueue<>();

        // Split file into front and back chunks
        int totalChunks = (int) Math.ceil((double) fileSize / INITIAL_CHUNK_SIZE);
        int midPoint = totalChunks / 2;

        for (int i = 0; i < midPoint; i++) {
            frontChunks.offer(i);
        }
        for (int i = totalChunks - 1; i >= midPoint; i--) {
            backChunks.offer(i);
        }

        // Create download tasks for both connections
        Future<?>[] tasks = new Future[2];
        tasks[0] = executor.submit(() -> downloadChunksEnhanced(frontChunks, fileId, fileSize, 1));
        tasks[1] = executor.submit(() -> downloadChunksEnhanced(backChunks, fileId, fileSize, 2));

        // Monitor and adjust chunk sizes periodically
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
        monitor.scheduleAtFixedRate(() -> {
            for (int connId : Arrays.asList(1, 2)) {
                ConnectionStats stats = connectionStats.get(connId);
                adjustChunkSize(connId, (long) stats.getAverageRTT(), stats.packetsLost);
            }
        }, 1, 1, TimeUnit.SECONDS);

        // Wait for completion
        for (Future<?> task : tasks) {
            task.get();
        }

        executor.shutdown();
        monitor.shutdown();

        // Verify and print statistics
        String md5 = calculateMD5(fileData.toByteArray());
        printEnhancedStats(System.currentTimeMillis() - startTime, fileSize, md5);
    }

    private void downloadChunksEnhanced(ConcurrentLinkedQueue<Integer> chunks, int fileId, long fileSize,
            int connectionId) {
        while (true) {
            Integer chunkIndex = chunks.poll();
            if (chunkIndex == null)
                break;

            int chunkSize = dynamicChunkSizes.get(connectionId);
            long startByte = chunkIndex * chunkSize;
            long endByte = Math.min(startByte + chunkSize - 1, fileSize - 1);

            try {
                byte[] request = createRequest((byte) 3, (byte) fileId, (int) startByte, (int) endByte);
                request = compressData(request);

                long requestTime = System.currentTimeMillis();
                byte[] response = sendRequest(request, connectionId);

                if (response != null) {
                    response = decompressData(response);
                    long rtt = System.currentTimeMillis() - requestTime;
                    updateRTTHistory(connectionId, rtt);

                    ConnectionStats stats = connectionStats.get(connectionId);
                    if (stats != null) {
                        stats.updateThroughput(response.length);

                        synchronized (this) {
                            totalBytesDownloaded += response.length;
                            double progress = (double) totalBytesDownloaded / fileSize * 100;
                            System.out.printf("\rProgress: %.2f%% (Conn %d: %d B/s, Chunk: %d)",
                                    progress, connectionId, stats.lastThroughput, chunkSize);
                        }
                    } else {
                        System.err.println("Error: ConnectionStats for connectionId " + connectionId + " is null.");
                    }
                } else {
                    chunks.offer(chunkIndex);
                    ConnectionStats stats = connectionStats.get(connectionId);
                    if (stats != null) {
                        stats.packetsLost++;
                    } else {
                        System.err.println("Error: ConnectionStats for connectionId " + connectionId + " is null.");
                    }
                }
            } catch (Exception e) {
                chunks.offer(chunkIndex);
                ConnectionStats stats = connectionStats.get(connectionId);
                if (stats != null) {
                    stats.packetsLost++;
                } else {
                    System.err.println("Error: ConnectionStats for connectionId " + connectionId + " is null.");
                }
            }
        }
    }

    private void printEnhancedStats(long totalTime, long fileSize, String md5) {
        System.out.println("\n\nEnhanced Download Statistics:");
        System.out.println("Total time: " + totalTime + " ms");
        System.out.println("File size: " + fileSize + " bytes");
        System.out.println("Total bytes downloaded: " + totalBytesDownloaded);
        System.out.println("Compression ratio: " + String.format("%.2f%%",
                (1 - (double) totalBytesDownloaded / fileSize) * 100));
        System.out.println("MD5 hash: " + md5);

        for (Map.Entry<Integer, ConnectionStats> entry : connectionStats.entrySet()) {
            ConnectionStats stats = entry.getValue();
            int connId = entry.getKey();

            System.out.println("\nConnection " + connId + " Statistics:");
            System.out.println("Final chunk size: " + dynamicChunkSizes.get(connId));
            System.out.println("Average RTT: " + String.format("%.2f ms", stats.getAverageRTT()));
            System.out.println("Final timeout: " + dynamicTimeouts.get(connId) + " ms");
            System.out.println("Packets lost: " + stats.packetsLost);
            System.out.println("Throughput: " + stats.lastThroughput + " B/s");
            System.out.println("Total bytes transferred: " + stats.bytesTransferred);
        }
    }

    // Method to calculate MD5 hash
    private String calculateMD5(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] hashInBytes = md.digest(data);

        // Convert bytes to hex
        StringBuilder sb = new StringBuilder();
        for (byte b : hashInBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // Method to send request
    private byte[] sendRequest(byte[] request, int connectionId) throws IOException {
        DatagramSocket socket = (connectionId == 1) ? socket1 : socket2;
        InetAddress serverAddress = (connectionId == 1) ? serverAddress1 : serverAddress2;
        int serverPort = (connectionId == 1) ? serverPort1 : serverPort2;

        DatagramPacket packet = new DatagramPacket(request, request.length, serverAddress, serverPort);
        socket.send(packet);

        byte[] buffer = new byte[BUFFER_SIZE];
        DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
        socket.receive(responsePacket);

        return Arrays.copyOf(responsePacket.getData(), responsePacket.getLength());
    }

    // Method to create request
    private byte[] createRequest(byte command, byte fileId, int startByte, int endByte) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            outputStream.write(command);
            outputStream.write(fileId);
            outputStream.write(intToByteArray(startByte));
            outputStream.write(intToByteArray(endByte));
        } catch (IOException e) {
            e.printStackTrace();
        }
        return outputStream.toByteArray();
    }

    // Helper method to convert int to byte array
    private byte[] intToByteArray(int value) {
        return new byte[] {
                (byte) (value >> 24),
                (byte) (value >> 16),
                (byte) (value >> 8),
                (byte) value
        };
    }

    public static void main(String[] args) {
        try {
            dummyClient client = new dummyClient("172.17.0.2", 5000, "172.17.0.2", 5001);
            client.downloadFile(1, 5000); // Example call to downloadFile method
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}