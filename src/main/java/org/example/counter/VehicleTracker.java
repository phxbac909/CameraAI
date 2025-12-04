package org.example.counter;

import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.Rectangle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tracker với counting line ở giữa khung hình
 */
public class VehicleTracker {
    private final List<TrackedVehicle> activeVehicles;
    private int nextId;
    private int totalVehicleCount;

    // Thêm set để lưu trữ các vehicle đã đi qua line
    private final Set<Integer> countedVehicles;

    // Tham số tracking
    private final double iouThreshold;
    private final int maxMissingFrames;
    private final double missingIouThreshold;

    // Vị trí counting line (tọa độ Y của đường ngang)
    private double countingLineY;
    private boolean countingLineEnabled = true;

    /**
     * Constructor với tham số mặc định
     */
    public VehicleTracker() {
        this(0.05, 10);
    }

    /**
     * Constructor với tham số tùy chỉnh
     */
    public VehicleTracker(double iouThreshold, int maxMissingFrames) {
        this.activeVehicles = new ArrayList<>();
        this.nextId = 1;
        this.totalVehicleCount = 0;
        this.countedVehicles = new HashSet<>();

        this.iouThreshold = iouThreshold;
        this.maxMissingFrames = maxMissingFrames;
        this.missingIouThreshold = Math.min(iouThreshold * 2.5, 0.3);

        // Mặc định đặt counting line ở giữa khung hình
        // Giá trị này sẽ được cập nhật trong phương thức update()
        this.countingLineY = 0.5;

        System.out.println("🎯 Line-based Tracker initialized:");
        System.out.println("   - Normal IoU threshold: " + iouThreshold);
        System.out.println("   - Missing IoU threshold: " + missingIouThreshold);
        System.out.println("   - Counting line enabled at Y = " + countingLineY);
    }

    /**
     * Update tracker với detections mới và thực hiện counting
     */
    public void update(List<Detection> detections, double imageHeight) {
        // Cập nhật vị trí counting line nếu có imageHeight
        if (imageHeight > 0) {
            this.countingLineY = imageHeight / 2;
        }

        // Bước 1: Tăng missing counter cho tất cả vehicles
        for (TrackedVehicle vehicle : activeVehicles) {
            vehicle.incrementMissingFrames();
        }

        // Bước 2: Match detections với tracked vehicles
        boolean[] matchedDetections = new boolean[detections.size()];
        boolean[] matchedVehicles = new boolean[activeVehicles.size()];

        // Tìm best match cho mỗi vehicle
        for (int i = 0; i < activeVehicles.size(); i++) {
            TrackedVehicle vehicle = activeVehicles.get(i);

            double bestIoU = 0;
            int bestDetectionIdx = -1;

            BoundingBox vehicleBox = vehicle.getMissingFrames() > 0
                    ? vehicle.getPredictedBoundingBox()
                    : vehicle.getBoundingBox();

            double currentIouThreshold = vehicle.getMissingFrames() > 0
                    ? missingIouThreshold
                    : iouThreshold;

            for (int j = 0; j < detections.size(); j++) {
                if (matchedDetections[j]) continue;

                double iou = calculateIoU(
                        vehicleBox,
                        detections.get(j).getBoundingBox()
                );

                if (iou > bestIoU && iou >= currentIouThreshold) {
                    bestIoU = iou;
                    bestDetectionIdx = j;
                }
            }

            // Nếu tìm thấy match
            if (bestDetectionIdx >= 0) {
                // Lưu center Y cũ để kiểm tra crossing
                double oldCenterY = vehicle.getBoundingBox().getBounds().getY() +
                        vehicle.getBoundingBox().getBounds().getHeight() / 2;

                // Update vehicle
                vehicle.update(detections.get(bestDetectionIdx));
                matchedDetections[bestDetectionIdx] = true;
                matchedVehicles[i] = true;

                // Kiểm tra vehicle có đi qua counting line không
                if (countingLineEnabled) {
                    checkAndCountLineCrossing(vehicle, oldCenterY);
                }

                if (vehicle.getMissingFrames() == 0) {
                    System.out.println("✅ Re-tracked vehicle after missing: " + vehicle);
                }
            }
        }

        // Bước 3: Tạo tracked vehicle mới cho detections chưa match
        for (int i = 0; i < detections.size(); i++) {
            if (!matchedDetections[i]) {
                TrackedVehicle newVehicle = new TrackedVehicle(nextId++, detections.get(i));
                activeVehicles.add(newVehicle);

                // Kiểm tra nếu vehicle mới đã đi qua line ngay từ đầu
                if (countingLineEnabled) {
                    double centerY = detections.get(i).getCenterY();
                    // Nếu vehicle xuất hiện bên dưới line (đang đi lên)
                    if (centerY > countingLineY) {
                        countedVehicles.add(newVehicle.getId());
                    }
                }

                System.out.println("🆕 New vehicle detected: " + newVehicle);
            }
        }

        // Bước 4: Xóa vehicles bị mất quá lâu
        List<TrackedVehicle> lostVehicles = new ArrayList<>();
        activeVehicles.removeIf(vehicle -> {
            if (vehicle.isLost(maxMissingFrames)) {
                lostVehicles.add(vehicle);
                return true;
            }
            return false;
        });

        // Log lost vehicles
        for (TrackedVehicle vehicle : lostVehicles) {
            System.out.println("❌ Vehicle lost: " + vehicle);
        }
    }

    /**
     * Kiểm tra và đếm khi vehicle đi qua counting line
     */
    private void checkAndCountLineCrossing(TrackedVehicle vehicle, double oldCenterY) {
        int vehicleId = vehicle.getId();

        // Nếu vehicle đã được đếm rồi thì bỏ qua
        if (countedVehicles.contains(vehicleId)) {
            return;
        }

        double currentCenterY = vehicle.getBoundingBox().getBounds().getY() +
                vehicle.getBoundingBox().getBounds().getHeight() / 2;

        // Kiểm tra xem vehicle có đi qua line không
        // Đi từ trên xuống dưới (đi vào khung hình)
        if (oldCenterY <= countingLineY && currentCenterY > countingLineY) {
            // Hoặc đi từ dưới lên trên (đi ra khỏi khung hình)
            // if (oldCenterY >= countingLineY && currentCenterY < countingLineY)

            countedVehicles.add(vehicleId);
            totalVehicleCount++;

            System.out.println("🎯 Vehicle crossed counting line!");
            System.out.println("   ID: " + vehicleId);
            System.out.println("   Type: " + vehicle.getClassName());
            System.out.println("   Direction: " + (oldCenterY < currentCenterY ? "Down" : "Up"));
            System.out.println("   Total count: " + totalVehicleCount);
        }
    }

    /**
     * Tính IoU (Intersection over Union) giữa 2 bounding boxes
     */
    private double calculateIoU(BoundingBox box1, BoundingBox box2) {
        Rectangle r1 = box1.getBounds();
        Rectangle r2 = box2.getBounds();

        double x1 = Math.max(r1.getX(), r2.getX());
        double y1 = Math.max(r1.getY(), r2.getY());
        double x2 = Math.min(r1.getX() + r1.getWidth(), r2.getX() + r2.getWidth());
        double y2 = Math.min(r1.getY() + r1.getHeight(), r2.getY() + r2.getHeight());

        double intersectionWidth = Math.max(0, x2 - x1);
        double intersectionHeight = Math.max(0, y2 - y1);
        double intersection = intersectionWidth * intersectionHeight;

        double area1 = r1.getWidth() * r1.getHeight();
        double area2 = r2.getWidth() * r2.getHeight();
        double union = area1 + area2 - intersection;

        if (union < 1e-6) {
            return 0;
        }

        return intersection / union;
    }

    /**
     * Lấy tổng số phương tiện đã đi qua line
     */
    public int getTotalVehicleCount() {
        return totalVehicleCount;
    }

    /**
     * Lấy số phương tiện đang active (đang trong frame)
     */
    public int getActiveVehicleCount() {
        return activeVehicles.size();
    }

    /**
     * Lấy danh sách vehicles đang active
     */
    public List<TrackedVehicle> getActiveVehicles() {
        return new ArrayList<>(activeVehicles);
    }

    /**
     * Lấy vị trí Y của counting line
     */
    public double getCountingLineY() {
        return countingLineY;
    }

    /**
     * Đặt vị trí Y cho counting line (0-1 hoặc pixel value)
     */
    public void setCountingLineY(double countingLineY) {
        this.countingLineY = countingLineY;
    }

    /**
     * Bật/tắt counting line
     */
    public void setCountingLineEnabled(boolean enabled) {
        this.countingLineEnabled = enabled;
    }

    /**
     * Kiểm tra xem một vehicle đã được đếm chưa
     */
    public boolean isVehicleCounted(int vehicleId) {
        return countedVehicles.contains(vehicleId);
    }

    /**
     * Reset tracker về trạng thái ban đầu
     */
    public void reset() {
        activeVehicles.clear();
        countedVehicles.clear();
        nextId = 1;
        totalVehicleCount = 0;
        System.out.println("🔄 Tracker reset - All counts cleared");
    }
}