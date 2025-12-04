package org.example.counter_v2;

import ai.djl.Application;
import ai.djl.inference.Predictor;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import ai.djl.translate.TranslateException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vehicle Counter Service - Chỉ track và đếm vehicles trong vùng counting line
 */
public class VehicleCounterService_v1 {

    private ZooModel<Image, DetectedObjects> model;
    private Predictor<Image, DetectedObjects> predictor;

    // Tracking state
    private Map<Integer, TrackedVehicle> activeVehicles;
    private int nextVehicleId;
    private int totalCount;
    private int frameNumber;

    // Configuration
    private static final float CONFIDENCE_THRESHOLD = 0.1f;
    private static final float IOU_THRESHOLD = 0.3f;
    private static final int MAX_FRAMES_TO_SKIP = 10;
    private static final double COUNTING_LINE_Y_RATIO = 0.6;
    private static final int COUNTING_LINE_MARGIN = 50;

    // Debug
    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS");

    // Vehicle classes
    private static final Set<String> VEHICLE_CLASSES = new HashSet<>(Arrays.asList(
            "car", "motorcycle", "motorbike", "truck", "bus"
    ));

    /**
     * Initialize service
     */
    public VehicleCounterService_v1() throws Exception {
        System.out.println("\n╔════════════════════════════════════════════════════╗");
        System.out.println("║     Vehicle Counter Service - YOLOv5s             ║");
        System.out.println("╚════════════════════════════════════════════════════╝");

        initializeModel();
        activeVehicles = new ConcurrentHashMap<>();
        nextVehicleId = 1;
        totalCount = 0;
        frameNumber = 0;

        printConfiguration();
        System.out.println("\n✓ Service initialized successfully!\n");
    }

    /**
     * Print configuration
     */
    private void printConfiguration() {
        System.out.println("\n┌─────────────────────────────────────────────────┐");
        System.out.println("│ Configuration:                                  │");
        System.out.println("├─────────────────────────────────────────────────┤");
        System.out.println(String.format("│ Confidence Threshold: %.2f                      │", CONFIDENCE_THRESHOLD));
        System.out.println(String.format("│ IOU Threshold: %.2f                             │", IOU_THRESHOLD));
        System.out.println(String.format("│ Counting Line Position: %.0f%% from top          │", COUNTING_LINE_Y_RATIO * 100));
        System.out.println(String.format("│ Counting Line Margin: %d pixels                │", COUNTING_LINE_MARGIN));
        System.out.println(String.format("│ Max Frames to Skip: %d                         │", MAX_FRAMES_TO_SKIP));
        System.out.println("└─────────────────────────────────────────────────┘");
    }

    /**
     * Initialize model
     */
    private void initializeModel() throws Exception {
        long loadStart = System.currentTimeMillis();

        Criteria<Image, DetectedObjects> criteria = Criteria.builder()
                .optApplication(Application.CV.OBJECT_DETECTION)
                .setTypes(Image.class, DetectedObjects.class)
                .optModelUrls("djl://ai.djl.pytorch/yolov5s")
                .optEngine("PyTorch")
                .optProgress(new ProgressBar())
                .build();

        try {
            model = criteria.loadModel();
            predictor = model.newPredictor();

            long loadTime = System.currentTimeMillis() - loadStart;
            System.out.println(String.format("[INIT] ✓ Model loaded successfully in %dms", loadTime));

        } catch (Exception e) {
            System.err.println("[ERROR] Failed to load model: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Process frame - Chỉ track vehicles trong vùng counting line
     */
    public synchronized int receiveImage(byte[] imageBytes) throws IOException {
        frameNumber++;
        long frameStartTime = System.currentTimeMillis();

        try {
            // Decode image
            Image image = ImageFactory.getInstance()
                    .fromInputStream(new ByteArrayInputStream(imageBytes));

            int imageHeight = image.getHeight();
            int imageWidth = image.getWidth();
            double countingLineY = imageHeight * COUNTING_LINE_Y_RATIO;

            // Detect vehicles
            DetectedObjects detections = predictor.predict(image);

            // Filter: CHỈ LẤY vehicles trong vùng counting line
            List<Detection> vehicles = filterVehicles(detections, imageWidth, imageHeight);
            List<Detection> vehiclesInZone = filterVehiclesInCountingZone(vehicles, countingLineY);

            // Update tracking
            updateTracking(vehiclesInZone);

            // Print frame summary
            long totalTime = System.currentTimeMillis() - frameStartTime;
            printFrameSummary(vehicles, totalTime);

            return totalCount;

        } catch (TranslateException e) {
            System.err.println("[ERROR] Frame #" + frameNumber + " - Error: " + e.getMessage());
            throw new IOException("Error processing image", e);
        }
    }

    /**
     * Filter: CHỈ LẤY vehicles đang ở trong vùng counting line
     */
    private List<Detection> filterVehicles(
            DetectedObjects detections, int imageWidth, int imageHeight) {

        List<Detection> filtered = new ArrayList<>();
        List<DetectedObjects.DetectedObject> items = detections.items();

        for (DetectedObjects.DetectedObject obj : items) {
            String className = obj.getClassName().toLowerCase();
            float confidence = (float) obj.getProbability();

            // Check vehicle class và confidence
            boolean isVehicle = VEHICLE_CLASSES.stream()
                    .anyMatch(vehicleClass -> className.contains(vehicleClass));

            if (isVehicle && confidence >= CONFIDENCE_THRESHOLD) {
                ai.djl.modality.cv.output.BoundingBox bbox = obj.getBoundingBox();
                ai.djl.modality.cv.output.Rectangle rect = bbox.getBounds();

                int x = (int)(rect.getX() * imageWidth);
                int y = (int)(rect.getY() * imageHeight);
                int width = (int)(rect.getWidth() * imageWidth);
                int height = (int)(rect.getHeight() * imageHeight);

                Detection det = new Detection(x, y, width, height, confidence, className);
                double centerY = det.getCenterY();

                // CHỈ LẤY vehicles
                filtered.add(det);

            }
        }

        return filtered;
    }

    private List<Detection> filterVehiclesInCountingZone(
            List<Detection> vehicles, double countingLineY) {

        List<Detection> filtered = new ArrayList<>();

        for (Detection vehicle : vehicles) {

            double centerY = vehicle.getCenterY();
            // CHỈ LẤY vehicles trong vùng counting line
                if (centerY >= countingLineY - COUNTING_LINE_MARGIN &&
                        centerY <= countingLineY + COUNTING_LINE_MARGIN) {
                    filtered.add(vehicle);
                }
            }
        return filtered;
    }

    /**
     * Update tracking - Chỉ track vehicles trong zone
     */
    private void updateTracking(List<Detection> detections) {
        // Increment frames without detection
        for (TrackedVehicle vehicle : activeVehicles.values()) {
            vehicle.framesWithoutDetection++;
        }

        // Match detections to tracked vehicles
        Map<Integer, Detection> matched = new HashMap<>();
        Set<Detection> unmatchedDetections = new HashSet<>(detections);

        // IOU-based matching
        for (TrackedVehicle vehicle : activeVehicles.values()) {
            Detection bestMatch = null;
            double bestIOU = IOU_THRESHOLD;

            for (Detection det : unmatchedDetections) {
                double iou = calculateIOU(vehicle.lastDetection, det);
                if (iou > bestIOU) {
                    bestIOU = iou;
                    bestMatch = det;
                }
            }

            if (bestMatch != null) {
                matched.put(vehicle.id, bestMatch);
                unmatchedDetections.remove(bestMatch);
                vehicle.framesWithoutDetection = 0;
            }
        }

        // Update matched vehicles
        for (Map.Entry<Integer, Detection> entry : matched.entrySet()) {
            TrackedVehicle vehicle = activeVehicles.get(entry.getKey());
            vehicle.lastDetection = entry.getValue();
        }

        // Remove lost vehicles
        List<Integer> removedVehicles = new ArrayList<>();
        activeVehicles.entrySet().removeIf(entry -> {
            boolean shouldRemove = entry.getValue().framesWithoutDetection > MAX_FRAMES_TO_SKIP;
            if (shouldRemove) {
                removedVehicles.add(entry.getKey());
            }
            return shouldRemove;
        });

        // Print removed vehicles
        for (Integer vehicleId : removedVehicles) {
            System.out.println(String.format("❌ Vehicle removed: ID #%d (lost tracking)", vehicleId));
        }

        // Add NEW vehicles - MỖI VEHICLE MỚI = 1 COUNT
        for (Detection det : unmatchedDetections) {
            TrackedVehicle newVehicle = new TrackedVehicle(nextVehicleId++, det);
            activeVehicles.put(newVehicle.id, newVehicle);

            // TĂNG TOTAL COUNT KHI CÓ VEHICLE MỚI
            totalCount++;

            System.out.println(String.format("➕ New vehicle: ID #%d | Type: %s | Conf: %.2f%% | Pos: (%.0f, %.0f) | ✅ TOTAL: %d",
                    newVehicle.id,
                    det.className.toUpperCase(),
                    det.confidence * 100,
                    det.getCenterX(),
                    det.getCenterY(),
                    totalCount));
        }
    }

    /**
     * Print frame summary - CHỈ HIỂN thị frame, time, vehicles, confidence
     */
    private void printFrameSummary(List<Detection> detections, long totalTime) {
        System.out.println(String.format(
                "[Frame #%d] Time: %dms | Vehicles in zone: %d | Active: %d | Total: %d | Conf: %.2f",
                frameNumber,
                totalTime,
                detections.size(),
                activeVehicles.size(),
                totalCount,
                CONFIDENCE_THRESHOLD));

        // Print tất cả vehicles được detect trong zone
        for (int i = 0; i < detections.size(); i++) {
            Detection det = detections.get(i);
            System.out.println(String.format("  └─ [%d] %s | Conf: %.2f%% | Pos: (%.0f, %.0f)",
                    i + 1,
                    det.className.toUpperCase(),
                    det.confidence * 100,
                    det.getCenterX(),
                    det.getCenterY()));
        }
    }

    /**
     * Calculate IOU
     */
    private double calculateIOU(Detection det1, Detection det2) {
        int x1 = Math.max(det1.x, det2.x);
        int y1 = Math.max(det1.y, det2.y);
        int x2 = Math.min(det1.x + det1.width, det2.x + det2.width);
        int y2 = Math.min(det1.y + det1.height, det2.y + det2.height);

        if (x2 < x1 || y2 < y1) {
            return 0.0;
        }

        int intersectionArea = (x2 - x1) * (y2 - y1);
        int det1Area = det1.width * det1.height;
        int det2Area = det2.width * det2.height;
        int unionArea = det1Area + det2Area - intersectionArea;

        return (double) intersectionArea / unionArea;
    }

    /**
     * Reset service
     */
    public synchronized int reset() {
        int finalCount = totalCount;

        System.out.println("\n╔════════════════════════════════════════════════════╗");
        System.out.println("║              SERVICE RESET                         ║");
        System.out.println("╠════════════════════════════════════════════════════╣");
        System.out.println(String.format("║ Total frames: %d                                   ║", frameNumber));
        System.out.println(String.format("║ Total count: %d                                    ║", finalCount));
        System.out.println(String.format("║ Avg per frame: %.2f                                ║",
                frameNumber > 0 ? (double)finalCount / frameNumber : 0));
        System.out.println("╚════════════════════════════════════════════════════╝\n");

        activeVehicles.clear();
        nextVehicleId = 1;
        totalCount = 0;
        frameNumber = 0;

        return finalCount;
    }

    /**
     * Get current count
     */
    public synchronized int getTotalCount() {
        return totalCount;
    }

    /**
     * Get active vehicle count
     */
    public synchronized int getActiveVehicleCount() {
        return activeVehicles.size();
    }

    /**
     * Get frame number
     */
    public synchronized int getFrameNumber() {
        return frameNumber;
    }

    /**
     * Close resources
     */
    public void close() {
        System.out.println("\n[SHUTDOWN] Closing service...");

        if (predictor != null) {
            predictor.close();
        }
        if (model != null) {
            model.close();
        }

        System.out.println("[SHUTDOWN] ✓ Service closed successfully.");
    }

    /**
     * Detection class
     */
    private static class Detection {
        int x, y, width, height;
        float confidence;
        String className;

        Detection(int x, int y, int width, int height, float confidence, String className) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.confidence = confidence;
            this.className = className;
        }

        double getCenterX() {
            return x + width / 2.0;
        }

        double getCenterY() {
            return y + height / 2.0;
        }
    }

    /**
     * Tracked vehicle class
     */
    private static class TrackedVehicle {
        int id;
        Detection lastDetection;
        int framesWithoutDetection;

        TrackedVehicle(int id, Detection detection) {
            this.id = id;
            this.lastDetection = detection;
            this.framesWithoutDetection = 0;
        }
    }
}