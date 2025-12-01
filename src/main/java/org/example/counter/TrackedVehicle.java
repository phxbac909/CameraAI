package com.example.motorcontrol.vehiclecounter;

import ai.djl.modality.cv.output.BoundingBox;

/**
 * Class đại diện cho 1 phương tiện đang được tracking qua các frame
 */
public class TrackedVehicle {
    private final int id;
    private BoundingBox boundingBox;
    private String className;
    private int missingFrames;
    private int age;  // Số frame đã tồn tại

    public TrackedVehicle(int id, Detection detection) {
        this.id = id;
        this.boundingBox = detection.getBoundingBox();
        this.className = detection.getClassName();
        this.missingFrames = 0;
        this.age = 1;
    }

    /**
     * Update thông tin khi match với detection mới
     */
    public void update(Detection detection) {
        this.boundingBox = detection.getBoundingBox();
        this.className = detection.getClassName();
        this.missingFrames = 0;
        this.age++;
    }

    /**
     * Tăng counter khi không detect được trong frame hiện tại
     */
    public void incrementMissingFrames() {
        this.missingFrames++;
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

    /**
     * Kiểm tra xe có bị mất quá lâu không
     */
    public boolean isLost(int maxMissingFrames) {
        return missingFrames > maxMissingFrames;
    }

    @Override
    public String toString() {
        return String.format("Vehicle[ID=%d, type=%s, age=%d, missing=%d]",
                id, className, age, missingFrames);
    }
}