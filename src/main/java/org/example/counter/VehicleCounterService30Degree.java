//package org.example.counter;
//
//
//import ai.onnxruntime.*;
//import org.opencv.core.*;
//import org.opencv.imgcodecs.Imgcodecs;
//import org.opencv.imgproc.Imgproc;
//
//import java.nio.FloatBuffer;
//import java.util.*;
//import java.util.stream.Collectors;
//
///**
// * VehicleCounterService tối ưu cho góc nhìn 30° xuống
// * Camera đặt bên đường, cao 3m, góc 30° xuống mặt đường
// * Phù hợp với hình ảnh đã cung cấp
// */
//public class VehicleCounterService30Degree implements AutoCloseable {
//
//    private final OrtEnvironment env;
//    private final OrtSession session;
//    private final VehicleTracker tracker;
//    private final ROI roi;
//
//    private static final int INPUT_WIDTH = 640;
//    private static final int INPUT_HEIGHT = 640;
//
//    // ═══════════════════════════════════════════════════════════
//    // THAM SỐ TỐI ƯU CHO GÓC NHÌN 30° - DỰA TRÊN HÌNH ẢNH
//    // ═══════════════════════════════════════════════════════════
//
//    // Confidence: Vừa phải vì xe gần rõ, xe xa vẫn OK
//    private static final float CONF_THRESHOLD = 0.45f;
//
//    // IoU: Vừa phải vì xe thay đổi size không quá nhiều
//    private static final double IOU_THRESHOLD_TRACKING = 0.25;
//    private static final float IOU_THRESHOLD_NMS = 0.45f;
//
//    // Max missing: ~10 frames = 0.83s (xe có thể bị che bởi cột/vật)
//    private static final int MAX_MISSING_FRAMES = 10;
//
//    // Size filter: Loại bỏ người đi bộ, vật thể nhỏ
//    private static final boolean ENABLE_SIZE_FILTER = true;
//
//    // ═══════════════════════════════════════════════════════════
//
//    private static final Set<Integer> VEHICLE_CLASS_IDS = new HashSet<>(
//            Arrays.asList(2, 3, 5, 7)  // car, motorcycle, bus, truck
//    );
//
//    private static final Map<Integer, String> CLASS_NAMES = Map.of(
//            2, "car",
//            3, "motorcycle",
//            5, "bus",
//            7, "truck"
//    );
//
//    private int frameCount = 0;
//
//    static {
//        nu.pattern.OpenCV.loadLocally();
//    }
//
//    public VehicleCounterService30Degree(String modelPath) throws OrtException {
//        System.out.println("🚀 Initializing VehicleCounterService (30° Angle View)");
//        System.out.println("   Optimized for: Roadside camera, 3m high, 30° downward");
//        System.out.println("   Based on provided image analysis");
//        System.out.println("   Model: " + modelPath);
//        System.out.println("   Confidence: " + CONF_THRESHOLD);
//        System.out.println("   IoU Tracking: " + IOU_THRESHOLD_TRACKING);
//        System.out.println("   Max Missing: " + MAX_MISSING_FRAMES +
//                " (~" + String.format("%.2f", MAX_MISSING_FRAMES / 12.0) + "s)");
//        System.out.println("   Size Filter: " + (ENABLE_SIZE_FILTER ? "Enabled" : "Disabled"));
//
//        this.env = OrtEnvironment.getEnvironment();
//        this.session = env.createSession(modelPath, new OrtSession.SessionOptions());
//        this.tracker = new VehicleTracker(IOU_THRESHOLD_TRACKING, MAX_MISSING_FRAMES);
//        this.roi = new ROI();
//
//        System.out.println("✅ Service initialized successfully\n");
//    }
//
//    public int receiveImage(byte[] imageBytes) {
//        frameCount++;
//
//        System.out.println("\n" + "=".repeat(60));
//        System.out.println("📸 Frame #" + frameCount);
//        System.out.println("=".repeat(60));
//
//        try {
//            Mat frame = Imgcodecs.imdecode(new MatOfByte(imageBytes), Imgcodecs.IMREAD_COLOR);
//
//            if (frame.empty()) {
//                System.err.println("❌ Failed to decode image");
//                return tracker.getTotalVehicleCount();
//            }
//
//            long detectStart = System.currentTimeMillis();
//            List<Detection> allDetections = detectVehicles(frame);
//
//            // Filter 1: Size
//            List<Detection> validSize = allDetections;
//            if (ENABLE_SIZE_FILTER) {
//                validSize = allDetections.stream()
//                        .filter(this::isValidVehicleSize)
//                        .collect(Collectors.toList());
//
//                if (validSize.size() != allDetections.size()) {
//                    System.out.println("   ⚖️  Size filter: " + allDetections.size() +
//                            " → " + validSize.size());
//                }
//            }
//
//            // Filter 2: ROI
//            List<Detection> vehiclesInROI = validSize.stream()
//                    .filter(roi::isInside)
//                    .collect(Collectors.toList());
//
//            long detectTime = System.currentTimeMillis() - detectStart;
//
//            System.out.println("🔍 Detection: " + detectTime + "ms");
//            System.out.println("   Detected: " + allDetections.size());
//            System.out.println("   After filters: " + vehiclesInROI.size());
//
//            // Log each valid vehicle
//            for (Detection det : vehiclesInROI) {
//                System.out.println("   ✓ " + det);
//            }
//
//            tracker.update(vehiclesInROI);
//            printStatistics();
//
//            frame.release();
//            return tracker.getTotalVehicleCount();
//
//        } catch (Exception e) {
//            System.err.println("❌ Error: " + e.getMessage());
//            e.printStackTrace();
//            return tracker.getTotalVehicleCount();
//        }
//    }
//
//    /**
//     * ROI cho góc nhìn này (dựa trên hình ảnh)
//     */
//    private static class ROI {
//        // Tọa độ normalized [0.0 - 1.0]
//        private final double minX = 0.15;  // Bỏ lề trái (người đi bộ)
//        private final double maxX = 0.65;  // Bỏ xưởng bên phải
//        private final double minY = 0.20;  // Bỏ phần xa (mờ nhòe)
//        private final double maxY = 0.85;  // Bỏ phần quá gần camera
//
//        boolean isInside(Detection det) {
//            double cx = det.getCenterX();
//            double cy = det.getCenterY();
//
//            boolean inside = cx >= minX && cx <= maxX &&
//                    cy >= minY && cy <= maxY;
//
//            if (!inside) {
//                System.out.println("   ⊗ Outside ROI: " + det);
//            }
//
//            return inside;
//        }
//    }
//
//    /**
//     * Lọc theo kích thước để tránh false positive
//     */
//    private boolean isValidVehicleSize(Detection det) {
//        ai.djl.modality.cv.output.Rectangle rect =
//                det.getBoundingBox().getBounds();
//
//        double width = rect.getWidth();
//        double height = rect.getHeight();
//        double area = width * height;
//
//        // Kích thước tối thiểu/tối đa (normalized, 0-1)
//        boolean valid = false;
//        String reason = "";
//
//        if (det.getClassName().equals("motorcycle")) {
//            // Xe máy: 1.5% - 20% diện tích ảnh
//            valid = area >= 0.015 && area <= 0.20;
//            reason = String.format("motorcycle area %.3f (valid: 0.015-0.20)", area);
//        } else if (det.getClassName().equals("car") ||
//                det.getClassName().equals("truck") ||
//                det.getClassName().equals("bus")) {
//            // Xe hơi/truck: 3% - 35% diện tích ảnh
//            valid = area >= 0.03 && area <= 0.35;
//            reason = String.format("%s area %.3f (valid: 0.03-0.35)",
//                    det.getClassName(), area);
//        } else {
//            valid = true;
//        }
//
//        if (!valid) {
//            System.out.println("   ⊗ Invalid size: " + det + " (" + reason + ")");
//        }
//
//        return valid;
//    }
//
//    private List<Detection> detectVehicles(Mat frame) throws OrtException {
//        Mat preprocessed = preprocessImage(frame);
//        float[] inputData = matToFloatArray(preprocessed);
//
//        long[] shape = {1, 3, INPUT_HEIGHT, INPUT_WIDTH};
//        OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape);
//
//        Map<String, OnnxTensor> inputs = Collections.singletonMap("images", tensor);
//        OrtSession.Result results = session.run(inputs);
//
//        float[][] output = parseOutput(results);
//        List<Detection> detections = processOutput(output, frame.width(), frame.height());
//
//        tensor.close();
//        results.close();
//        preprocessed.release();
//
//        return detections;
//    }
//
//    private Mat preprocessImage(Mat frame) {
//        Mat rgb = new Mat();
//        Imgproc.cvtColor(frame, rgb, Imgproc.COLOR_BGR2RGB);
//
//        double scale = Math.min(
//                (double) INPUT_WIDTH / frame.width(),
//                (double) INPUT_HEIGHT / frame.height()
//        );
//
//        int newWidth = (int) (frame.width() * scale);
//        int newHeight = (int) (frame.height() * scale);
//
//        Mat resized = new Mat();
//        Imgproc.resize(rgb, resized, new Size(newWidth, newHeight));
//
//        Mat padded = new Mat(INPUT_HEIGHT, INPUT_WIDTH, CvType.CV_8UC3,
//                new Scalar(114, 114, 114));
//        int top = (INPUT_HEIGHT - newHeight) / 2;
//        int left = (INPUT_WIDTH - newWidth) / 2;
//
//        Rect roi = new Rect(left, top, newWidth, newHeight);
//        resized.copyTo(padded.submat(roi));
//
//        rgb.release();
//        resized.release();
//
//        return padded;
//    }
//
//    private float[] matToFloatArray(Mat mat) {
//        float[] data = new float[3 * INPUT_HEIGHT * INPUT_WIDTH];
//        byte[] byteData = new byte[mat.rows() * mat.cols() * mat.channels()];
//        mat.get(0, 0, byteData);
//
//        int idx = 0;
//        for (int c = 0; c < 3; c++) {
//            for (int h = 0; h < INPUT_HEIGHT; h++) {
//                for (int w = 0; w < INPUT_WIDTH; w++) {
//                    int pixelIdx = (h * INPUT_WIDTH + w) * 3 + c;
//                    data[idx++] = (byteData[pixelIdx] & 0xFF) / 255.0f;
//                }
//            }
//        }
//        return data;
//    }
//
//    private float[][] parseOutput(OrtSession.Result results) throws OrtException {
//        OnnxValue value = results.get(0);
//        float[][][] output = (float[][][]) value.getValue();
//
//        int numPredictions = output[0][0].length;
//        int numFeatures = output[0].length;
//
//        float[][] transposed = new float[numPredictions][numFeatures];
//        for (int i = 0; i < numPredictions; i++) {
//            for (int j = 0; j < numFeatures; j++) {
//                transposed[i][j] = output[0][j][i];
//            }
//        }
//
//        return transposed;
//    }
//
//    private List<Detection> processOutput(float[][] output, int frameWidth, int frameHeight) {
//        List<Detection> detections = new ArrayList<>();
//
//        double scaleX = (double) frameWidth / INPUT_WIDTH;
//        double scaleY = (double) frameHeight / INPUT_HEIGHT;
//
//        for (float[] pred : output) {
//            float maxScore = 0;
//            int maxClassId = -1;
//
//            for (int i = 4; i < pred.length; i++) {
//                if (pred[i] > maxScore) {
//                    maxScore = pred[i];
//                    maxClassId = i - 4;
//                }
//            }
//
//            if (maxScore >= CONF_THRESHOLD && VEHICLE_CLASS_IDS.contains(maxClassId)) {
//                SimpleBoundingBox bbox = new SimpleBoundingBox(
//                        pred[0] * scaleX,
//                        pred[1] * scaleY,
//                        pred[2] * scaleX,
//                        pred[3] * scaleY
//                );
//
//                Detection det = new Detection(bbox, CLASS_NAMES.get(maxClassId), maxScore);
//                detections.add(det);
//            }
//        }
//
//        return applyNMS(detections);
//    }
//
//    private List<Detection> applyNMS(List<Detection> detections) {
//        detections.sort((a, b) -> Double.compare(b.getConfidence(), a.getConfidence()));
//
//        List<Detection> result = new ArrayList<>();
//        boolean[] suppressed = new boolean[detections.size()];
//
//        for (int i = 0; i < detections.size(); i++) {
//            if (suppressed[i]) continue;
//
//            Detection det1 = detections.get(i);
//            result.add(det1);
//
//            for (int j = i + 1; j < detections.size(); j++) {
//                if (suppressed[j]) continue;
//
//                Detection det2 = detections.get(j);
//                double iou = calculateIoU(
//                        (SimpleBoundingBox) det1.getBoundingBox(),
//                        (SimpleBoundingBox) det2.getBoundingBox()
//                );
//
//                if (iou > IOU_THRESHOLD_NMS) {
//                    suppressed[j] = true;
//                }
//            }
//        }
//
//        return result;
//    }
//
//    private double calculateIoU(SimpleBoundingBox box1, SimpleBoundingBox box2) {
//        double x1 = Math.max(box1.x - box1.w / 2, box2.x - box2.w / 2);
//        double y1 = Math.max(box1.y - box1.h / 2, box2.y - box2.h / 2);
//        double x2 = Math.min(box1.x + box1.w / 2, box2.x + box2.w / 2);
//        double y2 = Math.min(box1.y + box1.h / 2, box2.y + box2.h / 2);
//
//        double intersection = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
//        double union = box1.w * box1.h + box2.w * box2.h - intersection;
//
//        return intersection / (union + 1e-6);
//    }
//
//    private void printStatistics() {
//        System.out.println("\n📊 Statistics:");
//        System.out.println("   Total counted: " + tracker.getTotalVehicleCount());
//        System.out.println("   Active now: " + tracker.getActiveVehicleCount());
//
//        List<TrackedVehicle> activeVehicles = tracker.getActiveVehicles();
//        if (!activeVehicles.isEmpty()) {
//            System.out.println("   Active vehicles:");
//            for (TrackedVehicle vehicle : activeVehicles) {
//                String status = vehicle.getMissingFrames() > 0 ?
//                        " 👻 missing " + vehicle.getMissingFrames() : "";
//                System.out.println("      • " + vehicle + status);
//            }
//        }
//    }
//
//    public int getTotalVehicleCount() {
//        return tracker.getTotalVehicleCount();
//    }
//
//    public void printFinalSummary() {
//        System.out.println("\n" + "=".repeat(60));
//        System.out.println("📈 FINAL SUMMARY (30° Angle View)");
//        System.out.println("=".repeat(60));
//        System.out.println("Frames processed: " + frameCount);
//        System.out.println("Total vehicles: " + tracker.getTotalVehicleCount());
//        System.out.println("=".repeat(60) + "\n");
//    }
//
//    @Override
//    public void close() {
//        try {
//            if (session != null) session.close();
//            if (env != null) env.close();
//            System.out.println("👋 Service closed");
//        } catch (OrtException e) {
//            e.printStackTrace();
//        }
//    }
//
//    private static class SimpleBoundingBox implements ai.djl.modality.cv.output.BoundingBox {
//        double x, y, w, h;
//
//        SimpleBoundingBox(double x, double y, double w, double h) {
//            this.x = x;
//            this.y = y;
//            this.w = w;
//            this.h = h;
//        }
//
//        @Override
//        public ai.djl.modality.cv.output.Rectangle getBounds() {
//            return new ai.djl.modality.cv.output.Rectangle(
//                    x - w / 2, y - h / 2, w, h
//            );
//        }
//
//        @Override
//        public Iterable<ai.djl.modality.cv.output.Point> getPath() {
//            return null;
//        }
//    }
//}