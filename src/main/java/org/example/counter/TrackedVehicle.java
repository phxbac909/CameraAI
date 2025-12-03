package org.example.counter;

import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.Rectangle;

/**
 * Class đại diện cho 1 phương tiện đang được tracking qua các frame
 * Version 2: Có dự đoán chuyển động (motion prediction)
 */
public class TrackedVehicle {
    private final int id;
    private BoundingBox boundingBox;
    private String className;
    private int missingFrames;
    private int age;  // Số frame đã tồn tại

    // Motion tracking
    private double lastCenterX;
    private double lastCenterY;
    private double velocityX;  // Vận tốc theo trục X
    private double velocityY;  // Vận tốc theo trục Y

    public TrackedVehicle(int id, Detection detection) {
        this.id = id;
        this.boundingBox = detection.getBoundingBox();
        this.className = detection.getClassName();
        this.missingFrames = 0;
        this.age = 1;

        // Khởi tạo vị trí và vận tốc
        this.lastCenterX = detection.getCenterX();
        this.lastCenterY = detection.getCenterY();
        this.velocityX = 0;
        this.velocityY = 0;
    }

    /**
     * Update thông tin khi match với detection mới
     */
    public void update(Detection detection) {
        // Tính vận tốc dựa trên sự thay đổi vị trí
        double newCenterX = detection.getCenterX();
        double newCenterY = detection.getCenterY();

        // Nếu có missing frames, velocity đã được tích lũy
        // Nên ta cần normalize lại
        int framesPassed = missingFrames + 1;

        this.velocityX = (newCenterX - lastCenterX) / framesPassed;
        this.velocityY = (newCenterY - lastCenterY) / framesPassed;

        // Update bounding box và thông tin
        this.boundingBox = detection.getBoundingBox();
        this.className = detection.getClassName();
        this.lastCenterX = newCenterX;
        this.lastCenterY = newCenterY;
        this.missingFrames = 0;
        this.age++;
    }

    /**
     * Tăng counter khi không detect được trong frame hiện tại
     */
    public void incrementMissingFrames() {
        this.missingFrames++;
    }

    /**
     * Dự đoán bounding box ở vị trí tiếp theo dựa trên vận tốc
     * QUAN TRỌNG: Sử dụng để match khi xe bị missing
     */
    public BoundingBox getPredictedBoundingBox() {
        if (missingFrames == 0) {
            return boundingBox;
        }

        // Dự đoán vị trí mới dựa trên vận tốc
        Rectangle rect = boundingBox.getBounds();

        double predictedCenterX = lastCenterX + velocityX * missingFrames;
        double predictedCenterY = lastCenterY + velocityY * missingFrames;

        // Tạo bounding box mới với vị trí dự đoán
        double newX = predictedCenterX - rect.getWidth() / 2;
        double newY = predictedCenterY - rect.getHeight() / 2;

        // Đảm bảo tọa độ không âm
        newX = Math.max(0, newX);
        newY = Math.max(0, newY);

        return new ai.djl.modality.cv.output.Rectangle(
                newX, newY, rect.getWidth(), rect.getHeight()
        );
    }

    public int getId() {
        return id;
    }

    public BoundingBox getBoundingBox() {
        return boundingBox;
    }

    public String getClassName() {
        return className;
    }

    public int getMissingFrames() {
        return missingFrames;
    }

    public int getAge() {
        return age;
    }

    public double getVelocityX() {
        return velocityX;
    }

    public double getVelocityY() {
        return velocityY;
    }

    /**
     * Kiểm tra xe có bị mất quá lâu không
     */
    public boolean isLost(int maxMissingFrames) {
        return missingFrames > maxMissingFrames;
    }

    /**
     * Kiểm tra xe có đang di chuyển không (dựa trên velocity)
     */
    public boolean isMoving() {
        double speed = Math.sqrt(velocityX * velocityX + velocityY * velocityY);
        return speed > 1.0;  // Threshold 1 pixel/frame
    }

    @Override
    public String toString() {
        return String.format("Vehicle[ID=%d, type=%s, age=%d, missing=%d, vel=(%.1f,%.1f)]",
                id, className, age, missingFrames, velocityX, velocityY);
    }
}