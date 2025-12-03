package org.example.counter;

import ai.djl.Application;
import ai.djl.MalformedModelException;
import ai.djl.inference.Predictor;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import ai.djl.translate.TranslateException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;

/**
 * Service đếm số phương tiện trong video với tracking
 * Sử dụng DJL + YOLO
 */
public class VehicleCounterService1 implements AutoCloseable {

    private final Predictor<Image, DetectedObjects> predictor;
    private final VehicleTracker tracker;
    private final ImageFactory imageFactory;

    public static VehicleCounterService1 instanse;

    static {
        try {
            instanse = new VehicleCounterService1();
        } catch (ModelNotFoundException | MalformedModelException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    // Vehicle classes trong COCO dataset
    private static final Set<String> VEHICLE_CLASSES = new HashSet<>(
            Arrays.asList("car", "motorcycle", "bus", "truck")
    );

    // Confidence threshold cho detection
    private static final double CONFIDENCE_THRESHOLD = 0.1;

    // Counter
    private int frameCount = 0;
    private boolean headerPrinted = false;

    /**
     * Constructor - Khởi tạo model và tracker
     *
     * @throws ModelNotFoundException Nếu không tìm thấy model
     * @throws MalformedModelException Nếu model bị lỗi
     * @throws IOException Nếu lỗi I/O
     */
    public VehicleCounterService1() throws ModelNotFoundException, MalformedModelException, IOException {
        this(0.1, 10);
    }

    /**
     * Constructor với tham số tracking tùy chỉnh
     *
     * @param iouThreshold Ngưỡng IoU (0.1-0.5, khuyến nghị 0.3)
     * @param maxMissingFrames Số frame tối đa không detect (khuyến nghị 5-10)
     */
    public VehicleCounterService1(double iouThreshold, int maxMissingFrames)
            throws ModelNotFoundException, MalformedModelException, IOException {

        System.out.println("🚀 Initializing VehicleCounterService...");
        System.out.println("   IoU Threshold: " + iouThreshold);
        System.out.println("   Max Missing Frames: " + maxMissingFrames);

        // Load YOLO model từ DJL Model Zoo
        Criteria<Image, DetectedObjects> criteria = Criteria.builder()
                .optApplication(Application.CV.OBJECT_DETECTION)
                .setTypes(Image.class, DetectedObjects.class)
                .optModelUrls("djl://ai.djl.pytorch/yolov5s")  // 's' → 'm'
                .optEngine("PyTorch")
                .optProgress(new ProgressBar())
                .build();

        ZooModel<Image, DetectedObjects> model = criteria.loadModel();
        this.predictor = model.newPredictor();

        // Khởi tạo tracker
        this.tracker = new VehicleTracker(iouThreshold, maxMissingFrames);

        // Image factory
        this.imageFactory = ImageFactory.getInstance();

        System.out.println("✅ VehicleCounterService initialized successfully\n");
    }

    /**
     * Nhận và xử lý 1 image frame
     * Tự động tracking với các image trước đó
     *
     * @param imageBytes Byte array của image (JPG, PNG, etc.)
     * @return Số phương tiện tổng cộng đã đếm được
     */
    public int receiveImage(byte[] imageBytes) {
        frameCount++;

        if (imageBytes.length == 0) {
            int totalVehicleCount = tracker.getTotalVehicleCount();
            tracker.reset();
            return totalVehicleCount;

        }
        try {
            // Bước 1: Convert byte[] thành DJL Image
            Image image = imageFactory.fromInputStream(
                    new ByteArrayInputStream(imageBytes)
            );

            // Bước 2: Detect vehicles
            DetectedObjects detectedObjects = predictor.predict(image);

            // Bước 3: Filter chỉ lấy vehicles
            List<Detection> vehicles = filterVehicles(detectedObjects);

            // Bước 4: Update tracker
            tracker.update(vehicles);

            // Bước 5: In bảng thống kê
            printTableRow(vehicles);
            tracker.getTotalVehicleCount();

            return tracker.getActiveVehicleCount();

        } catch (TranslateException e) {
            System.err.println("❌ Error during detection: " + e.getMessage());
            e.printStackTrace();
            return tracker.getTotalVehicleCount();
        } catch (IOException e) {
            System.err.println("❌ Error reading image: " + e.getMessage());
            e.printStackTrace();
            return tracker.getTotalVehicleCount();
        }
    }

    /**
     * Lọc chỉ lấy vehicles với confidence > threshold
     */
    private List<Detection> filterVehicles(DetectedObjects detectedObjects) {
        List<Detection> vehicles = new ArrayList<>();

        List<DetectedObjects.DetectedObject> items = detectedObjects.items();

        for (DetectedObjects.DetectedObject obj : items) {
            String className = obj.getClassName();
            double confidence = obj.getProbability();

            if (VEHICLE_CLASSES.contains(className) && confidence >= CONFIDENCE_THRESHOLD) {
                Detection detection = new Detection(
                        obj.getBoundingBox(),
                        className,
                        confidence
                );
                vehicles.add(detection);
            }
        }

        return vehicles;
    }

    /**
     * In header của bảng
     */
    private void printTableHeader() {
        System.out.println("\n" + "=".repeat(100));
        System.out.printf("| %-8s | %-8s | %-35s | %-35s |%n",
                "Frame", "Total", "Current Vehicle", "Active Vehicle");
        System.out.println("=".repeat(100));
        headerPrinted = true;
    }

    /**
     * In một dòng trong bảng
     */
    private void printTableRow(List<Detection> currentDetections) {
        if (!headerPrinted) {
            printTableHeader();
        }

        int totalCount = tracker.getTotalVehicleCount();
        List<TrackedVehicle> activeVehicles = tracker.getActiveVehicles();

        // Format Current Vehicle
        String currentVehicleStr = formatVehicleList(currentDetections);

        // Format Active Vehicle
        String activeVehicleStr = formatTrackedVehicleList(activeVehicles);

        System.out.printf("| %-8d | %-8d | %-35s | %-35s |%n",
                frameCount,
                totalCount,
                currentVehicleStr,
                activeVehicleStr);
    }

    /**
     * Format danh sách detection thành string
     */
    private String formatVehicleList(List<Detection> detections) {
        if (detections.isEmpty()) {
            return "0";
        }

        // Đếm theo loại
        Map<String, Integer> counts = new HashMap<>();
        for (Detection d : detections) {
            counts.put(d.getClassName(), counts.getOrDefault(d.getClassName(), 0) + 1);
        }

        // Build string
        StringBuilder sb = new StringBuilder();
        sb.append(detections.size()).append(" (");

        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            parts.add(entry.getValue() + " " + entry.getKey());
        }
        sb.append(String.join(", ", parts));
        sb.append(")");

        return sb.toString();
    }

    /**
     * Format danh sách tracked vehicle thành string
     */
    private String formatTrackedVehicleList(List<TrackedVehicle> vehicles) {
        if (vehicles.isEmpty()) {
            return "0";
        }

        // Đếm theo loại
        Map<String, Integer> counts = new HashMap<>();
        for (TrackedVehicle v : vehicles) {
            String className = v.getClassName();
            counts.put(className, counts.getOrDefault(className, 0) + 1);
        }

        // Build string
        StringBuilder sb = new StringBuilder();
        sb.append(vehicles.size()).append(" (");

        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            parts.add(entry.getValue() + " " + entry.getKey());
        }
        sb.append(String.join(", ", parts));
        sb.append(")");

        return sb.toString();
    }

    /**
     * Lấy tổng số phương tiện đã đếm
     */
    public int getTotalVehicleCount() {
        return tracker.getTotalVehicleCount();
    }

    /**
     * Lấy số phương tiện đang active trong frame
     */
    public int getActiveVehicleCount() {
        return tracker.getActiveVehicleCount();
    }

    /**
     * Lấy danh sách vehicles đang active
     */
    public List<TrackedVehicle> getActiveVehicles() {
        return tracker.getActiveVehicles();
    }

    /**
     * Reset service về trạng thái ban đầu
     */
    public void reset() {
        tracker.reset();
        frameCount = 0;
        headerPrinted = false;
        System.out.println("🔄 Service reset");
    }

    /**
     * Đóng resources
     */
    @Override
    public void close() {
        if (predictor != null) {
            predictor.close();
        }
        System.out.println("\n" + "=".repeat(100));
        System.out.println("👋 VehicleCounterService closed");
    }

    /**
     * In summary cuối cùng
     */
    public void printFinalSummary() {
        System.out.println("\n" + "=".repeat(100));
        System.out.println("📈 FINAL SUMMARY");
        System.out.println("=".repeat(100));
        System.out.println("Total frames processed: " + frameCount);
        System.out.println("Total vehicles counted: " + tracker.getTotalVehicleCount());
        System.out.println("=".repeat(100) + "\n");
    }
}