package org.example.counter;

import ai.djl.Application;
import ai.djl.MalformedModelException;
import ai.djl.inference.Predictor;
import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import ai.djl.translate.TranslateException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;

/**
 * Service đếm xe với ROI cropping để cải thiện detection
 */
public class VehicleCounterService implements AutoCloseable {

    private final Predictor<Image, DetectedObjects> predictor;
    private final VehicleTracker tracker;
    private final ImageFactory imageFactory;

    public static VehicleCounterService instanse;

    // ============ ROI CONFIGURATION ============
    // Định nghĩa vùng ROI (Region of Interest) - vùng đường xe đi qua
    // Tọa độ theo tỷ lệ % của ảnh (0.0 - 1.0)
    //
    // Dựa vào ảnh thực tế:
    // - Đường nằm ở bên TRÁI (0-35% chiều rộng)
    // - Vùng hữu ích từ 15-75% chiều cao (bỏ trời và đáy)
    private static final double ROI_X = 0.0;      // Bắt đầu từ mép trái
    private static final double ROI_Y = 0.15;     // Bắt đầu từ 15% (bỏ ít trời)
    private static final double ROI_WIDTH = 0.35; // Lấy 35% bên trái (chỉ vùng đường)
    private static final double ROI_HEIGHT = 0.6; // Lấy 60% chiều cao

    // Scale factor để zoom in ROI
    // Tăng lên 3.5x vì vùng ROI hẹp (chỉ 35% chiều rộng)
    private static final double SCALE_FACTOR = 3.5;

    // Enable/Disable ROI cropping
    private static final boolean USE_ROI = true;
    // ===========================================

    static {
        try {
            instanse = new VehicleCounterService();
        } catch (ModelNotFoundException | MalformedModelException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static final Set<String> VEHICLE_CLASSES = new HashSet<>(
            Arrays.asList("car", "motorcycle", "bus", "truck")
    );

    private static final double CONFIDENCE_THRESHOLD = 0.35;

    private int frameCount = 0;
    private boolean headerPrinted = false;

    public VehicleCounterService() throws ModelNotFoundException, MalformedModelException, IOException {
        this(0.15, 8);
    }

    public VehicleCounterService(double iouThreshold, int maxMissingFrames)
            throws ModelNotFoundException, MalformedModelException, IOException {

        System.out.println("🚀 Initializing VehicleCounterService...");
        System.out.println("   📹 Camera: High angle view");
        System.out.println("   ⚙️  IoU Threshold: " + iouThreshold);
        System.out.println("   ⚙️  Max Missing Frames: " + maxMissingFrames);
        System.out.println("   ⚙️  Confidence Threshold: " + CONFIDENCE_THRESHOLD);

        if (USE_ROI) {
            System.out.println("   ✂️  ROI Cropping: ENABLED");
            System.out.printf("      - Region: x=%.1f%%, y=%.1f%%, w=%.1f%%, h=%.1f%%%n",
                    ROI_X*100, ROI_Y*100, ROI_WIDTH*100, ROI_HEIGHT*100);
            System.out.println("      - Scale Factor: " + SCALE_FACTOR + "x");
        }

        // Load YOLOv5s (tốt nhất cho case này)
        Criteria<Image, DetectedObjects> criteria = Criteria.builder()
                .optApplication(Application.CV.OBJECT_DETECTION)
                .setTypes(Image.class, DetectedObjects.class)
                .optModelUrls("djl://ai.djl.pytorch/yolov5s")
                .optEngine("PyTorch")
                .optProgress(new ProgressBar())
                .build();

        ZooModel<Image, DetectedObjects> model = criteria.loadModel();
        this.predictor = model.newPredictor();
        this.tracker = new VehicleTracker(iouThreshold, maxMissingFrames);
        this.imageFactory = ImageFactory.getInstance();

        System.out.println("✅ VehicleCounterService initialized successfully\n");
    }

    /**
     * Nhận và xử lý 1 image frame
     */
    public int receiveImage(byte[] imageBytes) {
        frameCount++;

        try {
            // Load image
            Image originalImage = imageFactory.fromInputStream(
                    new ByteArrayInputStream(imageBytes)
            );

            // Crop và zoom ROI nếu enabled
            Image processedImage = USE_ROI ?
                    cropAndZoomROI(originalImage) : originalImage;

            // Detect vehicles
            DetectedObjects detectedObjects = predictor.predict(processedImage);

            // Debug 5 frame đầu
            if (frameCount <= 5) {
                System.out.println("\n🖼️  Frame " + frameCount + " info:");
                System.out.println("   Original size: " +
                        originalImage.getWidth() + "x" + originalImage.getHeight());
                if (USE_ROI) {
                    System.out.println("   Processed size: " +
                            processedImage.getWidth() + "x" + processedImage.getHeight());
                }
                debugAllDetections(detectedObjects);
            }

            // Filter vehicles
            List<Detection> vehicles = USE_ROI ?
                    convertROIDetectionsToOriginal(detectedObjects, originalImage) :
                    filterVehicles(detectedObjects);

            // Update tracker
            tracker.update(vehicles);
            printTableRow(vehicles);

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
     * Crop và zoom vùng ROI
     */
    private Image cropAndZoomROI(Image image) {
        int imgWidth = image.getWidth();
        int imgHeight = image.getHeight();

        // Tính toạ độ crop theo pixel
        int cropX = (int) (imgWidth * ROI_X);
        int cropY = (int) (imgHeight * ROI_Y);
        int cropWidth = (int) (imgWidth * ROI_WIDTH);
        int cropHeight = (int) (imgHeight * ROI_HEIGHT);

        // Crop
        Image cropped = image.getSubImage(cropX, cropY, cropWidth, cropHeight);

        // Zoom (scale up)
        int scaledWidth = (int) (cropWidth * SCALE_FACTOR);
        int scaledHeight = (int) (cropHeight * SCALE_FACTOR);

        return cropped.resize(scaledWidth, scaledHeight, false);
    }

    /**
     * Convert detections từ ROI coordinates về original image coordinates
     */
    private List<Detection> convertROIDetectionsToOriginal(
            DetectedObjects detectedObjects, Image originalImage) {

        List<Detection> originalDetections = new ArrayList<>();
        int imgWidth = originalImage.getWidth();
        int imgHeight = originalImage.getHeight();

        List<DetectedObjects.DetectedObject> items = detectedObjects.items();
        for (DetectedObjects.DetectedObject obj : items) {
            String className = obj.getClassName();
            double confidence = obj.getProbability();

            if (!VEHICLE_CLASSES.contains(className) || confidence < CONFIDENCE_THRESHOLD) {
                continue;
            }

            // Lấy bounding box từ ROI
            Rectangle roiRect = obj.getBoundingBox().getBounds();

            // Scale down từ zoomed size về crop size
            double cropX = roiRect.getX() / SCALE_FACTOR;
            double cropY = roiRect.getY() / SCALE_FACTOR;
            double cropW = roiRect.getWidth() / SCALE_FACTOR;
            double cropH = roiRect.getHeight() / SCALE_FACTOR;

            // Convert từ crop coordinates về original coordinates
            double originalX = cropX + (imgWidth * ROI_X);
            double originalY = cropY + (imgHeight * ROI_Y);

            // Tạo bounding box mới trong hệ tọa độ original
            Rectangle originalRect = new Rectangle(
                    originalX, originalY, cropW, cropH
            );

            Detection detection = new Detection(
                    originalRect,
                    className,
                    confidence
            );

            originalDetections.add(detection);
        }

        return originalDetections;
    }

    /**
     * Filter vehicles (dùng khi không có ROI)
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
//    private List<Detection> filterVehicles(DetectedObjects detectedObjects) {
//        List<Detection> vehicles = new ArrayList<>();
//
//        List<DetectedObjects.DetectedObject> items = detectedObjects.items();
//
//        for (DetectedObjects.DetectedObject obj : items) {
//            String className = obj.getClassName();
//            double confidence = obj.getProbability();
//
//            if (VEHICLE_CLASSES.contains(className) && confidence >= CONFIDENCE_THRESHOLD) {
//                Detection detection = new Detection(
//                        obj.getBoundingBox(),
//                        className,
//                        confidence
//                );
//                vehicles.add(detection);
//            }
//        }
//
//        return vehicles;
//    }

    /**
     * Debug all detections
     */
    private void debugAllDetections(DetectedObjects detectedObjects) {
        List<DetectedObjects.DetectedObject> allObjects = detectedObjects.items();

        System.out.println("   🔍 Total detections: " + allObjects.size());

        if (allObjects.isEmpty()) {
            System.out.println("   ❌ NO OBJECTS DETECTED!");
            return;
        }

        // Group by class
        Map<String, List<DetectedObjects.DetectedObject>> byClass = new HashMap<>();
        for (DetectedObjects.DetectedObject obj : allObjects) {
            byClass.computeIfAbsent(obj.getClassName(), k -> new ArrayList<>()).add(obj);
        }

        // Print classes
        System.out.println("   📊 Detected classes:");
        byClass.forEach((className, objects) -> {
            double maxConf = objects.stream()
                    .mapToDouble(DetectedObjects.DetectedObject::getProbability)
                    .max().orElse(0);

            String marker = VEHICLE_CLASSES.contains(className) ? "✅" : "  ";
            System.out.printf("      %s %-12s: %2d (max: %.3f)%n",
                    marker, className, objects.size(), maxConf);
        });
    }

    private void printTableHeader() {
        System.out.println("\n" + "=".repeat(100));
        System.out.printf("| %-8s | %-8s | %-35s | %-35s |%n",
                "Frame", "Total", "Current Vehicle", "Active Vehicle");
        System.out.println("=".repeat(100));
        headerPrinted = true;
    }

    private void printTableRow(List<Detection> currentDetections) {
        if (!headerPrinted) {
            printTableHeader();
        }

        int totalCount = tracker.getTotalVehicleCount();
        List<TrackedVehicle> activeVehicles = tracker.getActiveVehicles();

        String currentVehicleStr = formatVehicleList(currentDetections);
        String activeVehicleStr = formatTrackedVehicleList(activeVehicles);

        System.out.printf("| %-8d | %-8d | %-35s | %-35s |%n",
                frameCount, totalCount, currentVehicleStr, activeVehicleStr);
    }

    private String formatVehicleList(List<Detection> detections) {
        if (detections.isEmpty()) return "0";

        Map<String, Integer> counts = new HashMap<>();
        for (Detection d : detections) {
            counts.put(d.getClassName(), counts.getOrDefault(d.getClassName(), 0) + 1);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(detections.size()).append(" (");
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            parts.add(entry.getValue() + " " + entry.getKey());
        }
        sb.append(String.join(", ", parts)).append(")");
        return sb.toString();
    }

    private String formatTrackedVehicleList(List<TrackedVehicle> vehicles) {
        if (vehicles.isEmpty()) return "0";

        Map<String, Integer> counts = new HashMap<>();
        for (TrackedVehicle v : vehicles) {
            counts.put(v.getClassName(), counts.getOrDefault(v.getClassName(), 0) + 1);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(vehicles.size()).append(" (");
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            parts.add(entry.getValue() + " " + entry.getKey());
        }
        sb.append(String.join(", ", parts)).append(")");
        return sb.toString();
    }

    public int getTotalVehicleCount() {
        return tracker.getTotalVehicleCount();
    }

    public int getActiveVehicleCount() {
        return tracker.getActiveVehicleCount();
    }

    public List<TrackedVehicle> getActiveVehicles() {
        return tracker.getActiveVehicles();
    }

    public void reset() {
        tracker.reset();
        frameCount = 0;
        headerPrinted = false;
        System.out.println("🔄 Service reset");
    }

    @Override
    public void close() {
        if (predictor != null) {
            predictor.close();
        }
        System.out.println("\n" + "=".repeat(100));
        System.out.println("👋 VehicleCounterService closed");
    }

    public void printFinalSummary() {
        System.out.println("\n" + "=".repeat(100));
        System.out.println("📈 FINAL SUMMARY");
        System.out.println("=".repeat(100));
        System.out.println("Total frames processed: " + frameCount);
        System.out.println("Total vehicles counted: " + tracker.getTotalVehicleCount());
        System.out.println("=".repeat(100) + "\n");
    }
}