package org.example.counter;

import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.Rectangle;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracker với motion prediction để xử lý temporary occlusion
 */
public class VehicleTracker {
    private final List<TrackedVehicle> activeVehicles;
    private int nextId;
    private int totalVehicleCount;

    // Tham số tracking
    private final double iouThreshold;
    private final int maxMissingFrames;

    // THAM SỐ MỚI: IoU threshold khi xe đang missing
    // Khi xe bị missing, ta nới lỏng IoU threshold để dễ match hơn
    private final double missingIouThreshold;

    /**
     * Constructor với tham số mặc định
     */
    public VehicleTracker() {
        this(0.05, 10);
    }

    /**
     * Constructor với tham số tùy chỉnh
     *
     * @param iouThreshold Ngưỡng IoU để match khi detect liên tục
     * @param maxMissingFrames Số frame tối đa không detect được trước khi xóa
     */
    public VehicleTracker(double iouThreshold, int maxMissingFrames) {
        this.activeVehicles = new ArrayList<>();
        this.nextId = 1;
        this.totalVehicleCount = 0;
        this.iouThreshold = iouThreshold;
        this.maxMissingFrames = maxMissingFrames;

        // Khi xe missing, nới lỏng IoU threshold gấp đôi
        this.missingIouThreshold = Math.min(iouThreshold * 2.5, 0.3);

        System.out.println("🎯 Tracker initialized:");
        System.out.println("   - Normal IoU threshold: " + iouThreshold);
        System.out.println("   - Missing IoU threshold: " + missingIouThreshold);
    }

    /**
     * Update tracker với detections mới từ 1 frame
     *
     * @param detections Danh sách detections trong frame hiện tại
     */
    public void update(List<Detection> detections) {
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

            // QUAN TRỌNG: Sử dụng predicted bounding box nếu xe đang missing
            BoundingBox vehicleBox = vehicle.getMissingFrames() > 0
                    ? vehicle.getPredictedBoundingBox()
                    : vehicle.getBoundingBox();

            // Chọn IoU threshold phù hợp
            double currentIouThreshold = vehicle.getMissingFrames() > 0
                    ? missingIouThreshold
                    : iouThreshold;

            // Tìm detection có IoU cao nhất với vehicle này
            for (int j = 0; j < detections.size(); j++) {
                if (matchedDetections[j]) continue;  // Detection đã được match

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
                vehicle.update(detections.get(bestDetectionIdx));
                matchedDetections[bestDetectionIdx] = true;
                matchedVehicles[i] = true;

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
                totalVehicleCount++;

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
     * Tính IoU (Intersection over Union) giữa 2 bounding boxes
     */
    private double calculateIoU(BoundingBox box1, BoundingBox box2) {
        Rectangle r1 = box1.getBounds();
        Rectangle r2 = box2.getBounds();

        // Tìm vùng giao nhau
        double x1 = Math.max(r1.getX(), r2.getX());
        double y1 = Math.max(r1.getY(), r2.getY());
        double x2 = Math.min(r1.getX() + r1.getWidth(), r2.getX() + r2.getWidth());
        double y2 = Math.min(r1.getY() + r1.getHeight(), r2.getY() + r2.getHeight());

        // Tính diện tích giao
        double intersectionWidth = Math.max(0, x2 - x1);
        double intersectionHeight = Math.max(0, y2 - y1);
        double intersection = intersectionWidth * intersectionHeight;

        // Tính diện tích hợp
        double area1 = r1.getWidth() * r1.getHeight();
        double area2 = r2.getWidth() * r2.getHeight();
        double union = area1 + area2 - intersection;

        // Tránh chia cho 0
        if (union < 1e-6) {
            return 0;
        }

        return intersection / union;
    }

    /**
     * Lấy tổng số phương tiện đã đếm được
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
     * Reset tracker về trạng thái ban đầu
     */
    public void reset() {
        activeVehicles.clear();
        nextId = 1;
        totalVehicleCount = 0;
    }
}