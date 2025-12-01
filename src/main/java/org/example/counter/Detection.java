package com.example.motorcontrol.vehiclecounter;

import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.Rectangle;

/**
 * Class đại diện cho 1 phương tiện được phát hiện
 */
public class Detection {
    private final BoundingBox boundingBox;
    private final String className;
    private final double confidence;

    public Detection(BoundingBox boundingBox, String className, double confidence) {
        this.boundingBox = boundingBox;
        this.className = className;
        this.confidence = confidence;
    }

    public BoundingBox getBoundingBox() {
        return boundingBox;
    }

    public String getClassName() {
        return className;
    }

    public double getConfidence() {
        return confidence;
    }

    /**
     * Lấy tọa độ center của bounding box
     */
    public double getCenterX() {
        Rectangle rect = boundingBox.getBounds();
        return rect.getX() + rect.getWidth() / 2;
    }

    public double getCenterY() {
        Rectangle rect = boundingBox.getBounds();
        return rect.getY() + rect.getHeight() / 2;
    }

    @Override
    public String toString() {
        return String.format("%s (%.2f) at [%.0f, %.0f]",
                className, confidence, getCenterX(), getCenterY());
    }
}