package com.example.motorcontrol.vehiclecounter;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Service đếm số phương tiện trong video với tracking
 * Sử dụng DJL + YOLO
 */
public class VehicleCounterService implements AutoCloseable {

    private final Predictor<Image, DetectedObjects> predictor;
    private final VehicleTracker tracker;
    private final ImageFactory imageFactory;

    // Vehicle classes trong COCO dataset
    private static final Set<String> VEHICLE_CLASSES = new HashSet<>(
            Arrays.asList("car", "motorcycle", "bus", "truck")
    );

    // Confidence threshold cho detection
    private static final double CONFIDENCE_THRESHOLD = 0.5;

    // Counter
    private int frameCount = 0;

    /**
     * Constructor - Khởi tạo model và tracker
     *
     * @throws ModelNotFoundException Nếu không tìm thấy model
     * @throws MalformedModelException Nếu model bị lỗi
     * @throws IOException Nếu lỗi I/O
     */
    public VehicleCounterService() throws ModelNotFoundException, MalformedModelException, IOException {
        this(0.3, 5);
    }

    /**
     * Constructor với tham số tracking tùy chỉnh
     *
     * @param iouThreshold Ngưỡng IoU (0.1-0.5, khuyến nghị 0.3)
     * @param maxMissingFrames Số frame tối đa không detect (khuyến nghị 5-10)
     */
    public VehicleCounterService(double iouThreshold, int maxMissingFrames)
            throws ModelNotFoundException, MalformedModelException, IOException {

        System.out.println("🚀 Initializing VehicleCounterService...");
        System.out.println("   IoU Threshold: " + iouThreshold);
        System.out.println("   Max Missing Frames: " + maxMissingFrames);

        // Load YOLO model từ DJL Model Zoo
        Criteria<Image, DetectedObjects> criteria = Criteria.builder()
                .optApplication(Application.CV.OBJECT_DETECTION)
                .setTypes(Image.class, DetectedObjects.class)
                .optModelUrls("djl://ai.djl.pytorch/yolov5s")
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

        System.out.println("\n" + "=".repeat(60));
        System.out.println("📸 Processing Frame #" + frameCount);
        System.out.println("=".repeat(60));

        try {
            // Bước 1: Convert byte[] thành DJL Image
            Image image = imageFactory.fromInputStream(
                    new ByteArrayInputStream(imageBytes)
            );

            // Bước 2: Detect vehicles
            long detectStart = System.currentTimeMillis();
            DetectedObjects detectedObjects = predictor.predict(image);
            long detectTime = System.currentTimeMillis() - detectStart;

            // Bước 3: Filter chỉ lấy vehicles
            List<Detection> vehicles = filterVehicles(detectedObjects);

            System.out.println("🔍 Detection completed in " + detectTime + "ms");
            System.out.println("   Found " + vehicles.size() + " vehicle(s) in current frame");

            // Bước 4: Update tracker
            tracker.update(vehicles);

            // Bước 5: In thống kê
            printStatistics();

            return tracker.getTotalVehicleCount();

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

                System.out.println("   ✓ " + detection);
            }
        }

        return vehicles;
    }

    /**
     * In thống kê tracking
     */
    private void printStatistics() {
        System.out.println("\n📊 Tracking Statistics:");
        System.out.println("   Total vehicles counted: " + tracker.getTotalVehicleCount());
        System.out.println("   Currently active: " + tracker.getActiveVehicleCount());

        List<TrackedVehicle> activeVehicles = tracker.getActiveVehicles();
        if (!activeVehicles.isEmpty()) {
            System.out.println("\n   Active vehicles:");
            for (TrackedVehicle vehicle : activeVehicles) {
                System.out.println("      • " + vehicle);
            }
        }
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
        System.out.println("👋 VehicleCounterService closed");
    }

    /**
     * In summary cuối cùng
     */
    public void printFinalSummary() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("📈 FINAL SUMMARY");
        System.out.println("=".repeat(60));
        System.out.println("Total frames processed: " + frameCount);
        System.out.println("Total vehicles counted: " + tracker.getTotalVehicleCount());
        System.out.println("=".repeat(60) + "\n");
    }
}