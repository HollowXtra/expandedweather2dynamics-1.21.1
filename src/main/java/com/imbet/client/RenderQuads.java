package com.imbet.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

final class RenderQuads {
    private RenderQuads() {
    }

    static void quad(VertexConsumer consumer, PoseStack.Pose pose, float x1, float y1, float z1, float x2, float y2, float z2, int color) {
        int a = color >>> 24 & 255;
        int r = color >>> 16 & 255;
        int g = color >>> 8 & 255;
        int b = color & 255;
        consumer.addVertex(pose, x1, y1, z1).setColor(r, g, b, a);
        consumer.addVertex(pose, x2, y1, z1).setColor(r, g, b, a);
        consumer.addVertex(pose, x2, y2, z2).setColor(r, g, b, a);
        consumer.addVertex(pose, x1, y2, z2).setColor(r, g, b, a);
    }

    static void topQuad(VertexConsumer consumer, PoseStack.Pose pose, float minX, float minZ, float maxX, float maxZ, float y, int color) {
        int a = color >>> 24 & 255;
        int r = color >>> 16 & 255;
        int g = color >>> 8 & 255;
        int b = color & 255;
        consumer.addVertex(pose, minX, y, minZ).setColor(r, g, b, a);
        consumer.addVertex(pose, maxX, y, minZ).setColor(r, g, b, a);
        consumer.addVertex(pose, maxX, y, maxZ).setColor(r, g, b, a);
        consumer.addVertex(pose, minX, y, maxZ).setColor(r, g, b, a);
    }

    static void topQuadUv(VertexConsumer consumer, PoseStack.Pose pose, float minX, float minZ, float maxX, float maxZ, float y, int color) {
        int a = color >>> 24 & 255;
        int r = color >>> 16 & 255;
        int g = color >>> 8 & 255;
        int b = color & 255;
        consumer.addVertex(pose, minX, y, minZ).setUv(0.0F, 0.0F).setColor(r, g, b, a);
        consumer.addVertex(pose, maxX, y, minZ).setUv(1.0F, 0.0F).setColor(r, g, b, a);
        consumer.addVertex(pose, maxX, y, maxZ).setUv(1.0F, 1.0F).setColor(r, g, b, a);
        consumer.addVertex(pose, minX, y, maxZ).setUv(0.0F, 1.0F).setColor(r, g, b, a);
    }

    static void topPolyQuad(VertexConsumer consumer, PoseStack.Pose pose, float x1, float z1, float x2, float z2, float x3, float z3, float x4, float z4, float y, int color) {
        int a = color >>> 24 & 255;
        int r = color >>> 16 & 255;
        int g = color >>> 8 & 255;
        int b = color & 255;
        consumer.addVertex(pose, x1, y, z1).setColor(r, g, b, a);
        consumer.addVertex(pose, x2, y, z2).setColor(r, g, b, a);
        consumer.addVertex(pose, x3, y, z3).setColor(r, g, b, a);
        consumer.addVertex(pose, x4, y, z4).setColor(r, g, b, a);
    }

    static void topPolyQuadUv(VertexConsumer consumer, PoseStack.Pose pose, float x1, float z1, float x2, float z2, float x3, float z3, float x4, float z4, float y, int color) {
        int a = color >>> 24 & 255;
        int r = color >>> 16 & 255;
        int g = color >>> 8 & 255;
        int b = color & 255;
        consumer.addVertex(pose, x1, y, z1).setUv(0.0F, 0.0F).setColor(r, g, b, a);
        consumer.addVertex(pose, x2, y, z2).setUv(1.0F, 0.0F).setColor(r, g, b, a);
        consumer.addVertex(pose, x3, y, z3).setUv(1.0F, 1.0F).setColor(r, g, b, a);
        consumer.addVertex(pose, x4, y, z4).setUv(0.0F, 1.0F).setColor(r, g, b, a);
    }

    static void topLine(VertexConsumer consumer, PoseStack.Pose pose, float x1, float z1, float x2, float z2, float halfWidth, float y, int color) {
        float dx = x2 - x1;
        float dz = z2 - z1;
        float length = (float) Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4F) {
            topQuad(consumer, pose, x1 - halfWidth, z1 - halfWidth, x1 + halfWidth, z1 + halfWidth, y, color);
            return;
        }
        float px = -dz / length * halfWidth;
        float pz = dx / length * halfWidth;
        int a = color >>> 24 & 255;
        int r = color >>> 16 & 255;
        int g = color >>> 8 & 255;
        int b = color & 255;
        consumer.addVertex(pose, x1 + px, y, z1 + pz).setColor(r, g, b, a);
        consumer.addVertex(pose, x1 - px, y, z1 - pz).setColor(r, g, b, a);
        consumer.addVertex(pose, x2 - px, y, z2 - pz).setColor(r, g, b, a);
        consumer.addVertex(pose, x2 + px, y, z2 + pz).setColor(r, g, b, a);
    }

    static void topLineUv(VertexConsumer consumer, PoseStack.Pose pose, float x1, float z1, float x2, float z2, float halfWidth, float y, int color) {
        float dx = x2 - x1;
        float dz = z2 - z1;
        float length = (float) Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4F) {
            topQuadUv(consumer, pose, x1 - halfWidth, z1 - halfWidth, x1 + halfWidth, z1 + halfWidth, y, color);
            return;
        }
        float px = -dz / length * halfWidth;
        float pz = dx / length * halfWidth;
        int a = color >>> 24 & 255;
        int r = color >>> 16 & 255;
        int g = color >>> 8 & 255;
        int b = color & 255;
        consumer.addVertex(pose, x1 + px, y, z1 + pz).setUv(0.0F, 0.0F).setColor(r, g, b, a);
        consumer.addVertex(pose, x1 - px, y, z1 - pz).setUv(0.0F, 1.0F).setColor(r, g, b, a);
        consumer.addVertex(pose, x2 - px, y, z2 - pz).setUv(1.0F, 1.0F).setColor(r, g, b, a);
        consumer.addVertex(pose, x2 + px, y, z2 + pz).setUv(1.0F, 0.0F).setColor(r, g, b, a);
    }

    static void topRotatedQuadUv(VertexConsumer consumer, PoseStack.Pose pose, float centerX, float centerZ, float halfX, float halfZ, float angle, float y, int color) {
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        addRotatedVertex(consumer, pose, centerX, centerZ, -halfX, -halfZ, cos, sin, y, 0.0F, 0.0F, color);
        addRotatedVertex(consumer, pose, centerX, centerZ, halfX, -halfZ, cos, sin, y, 1.0F, 0.0F, color);
        addRotatedVertex(consumer, pose, centerX, centerZ, halfX, halfZ, cos, sin, y, 1.0F, 1.0F, color);
        addRotatedVertex(consumer, pose, centerX, centerZ, -halfX, halfZ, cos, sin, y, 0.0F, 1.0F, color);
    }

    private static void addRotatedVertex(VertexConsumer consumer, PoseStack.Pose pose, float centerX, float centerZ, float localX, float localZ, float cos, float sin, float y, float u, float v, int color) {
        int a = color >>> 24 & 255;
        int r = color >>> 16 & 255;
        int g = color >>> 8 & 255;
        int b = color & 255;
        float x = centerX + localX * cos - localZ * sin;
        float z = centerZ + localX * sin + localZ * cos;
        consumer.addVertex(pose, x, y, z).setUv(u, v).setColor(r, g, b, a);
    }
}
