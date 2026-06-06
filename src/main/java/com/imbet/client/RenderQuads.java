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
        consumer.vertex(pose.pose(), x1, y1, z1).color(r, g, b, a).endVertex();
        consumer.vertex(pose.pose(), x2, y1, z1).color(r, g, b, a).endVertex();
        consumer.vertex(pose.pose(), x2, y2, z2).color(r, g, b, a).endVertex();
        consumer.vertex(pose.pose(), x1, y2, z2).color(r, g, b, a).endVertex();
    }

    static void topQuad(VertexConsumer consumer, PoseStack.Pose pose, float minX, float minZ, float maxX, float maxZ, float y, int color) {
        int a = color >>> 24 & 255;
        int r = color >>> 16 & 255;
        int g = color >>> 8 & 255;
        int b = color & 255;
        consumer.vertex(pose.pose(), minX, y, minZ).color(r, g, b, a).endVertex();
        consumer.vertex(pose.pose(), maxX, y, minZ).color(r, g, b, a).endVertex();
        consumer.vertex(pose.pose(), maxX, y, maxZ).color(r, g, b, a).endVertex();
        consumer.vertex(pose.pose(), minX, y, maxZ).color(r, g, b, a).endVertex();
    }

    static void topQuadUv(VertexConsumer consumer, PoseStack.Pose pose, float minX, float minZ, float maxX, float maxZ, float y, int color) {
        int a = color >>> 24 & 255;
        int r = color >>> 16 & 255;
        int g = color >>> 8 & 255;
        int b = color & 255;
        consumer.vertex(pose.pose(), minX, y, minZ).color(r, g, b, a).endVertex();
        consumer.vertex(pose.pose(), maxX, y, minZ).color(r, g, b, a).endVertex();
        consumer.vertex(pose.pose(), maxX, y, maxZ).color(r, g, b, a).endVertex();
        consumer.vertex(pose.pose(), minX, y, maxZ).color(r, g, b, a).endVertex();
    }

    static void topLine(VertexConsumer consumer, PoseStack.Pose pose, float x1, float z1, float x2, float z2, float halfWidth, float y, int color) {
        topLineUv(consumer, pose, x1, z1, x2, z2, halfWidth, y, color);
    }

    static void topPolyQuad(VertexConsumer consumer, PoseStack.Pose pose, float x1, float z1, float x2, float z2, float x3, float z3, float x4, float z4, float y, int color) {
        int a = color >>> 24 & 255;
        int r = color >>> 16 & 255;
        int g = color >>> 8 & 255;
        int b = color & 255;
        consumer.vertex(pose.pose(), x1, y, z1).color(r, g, b, a).endVertex();
        consumer.vertex(pose.pose(), x2, y, z2).color(r, g, b, a).endVertex();
        consumer.vertex(pose.pose(), x3, y, z3).color(r, g, b, a).endVertex();
        consumer.vertex(pose.pose(), x4, y, z4).color(r, g, b, a).endVertex();
    }

    static void topPolyQuadUv(VertexConsumer consumer, PoseStack.Pose pose, float x1, float z1, float x2, float z2, float x3, float z3, float x4, float z4, float y, int color) {
        topPolyQuad(consumer, pose, x1, z1, x2, z2, x3, z3, x4, z4, y, color);
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
        consumer.vertex(pose.pose(), x1 + px, y, z1 + pz).color(r, g, b, a).endVertex();
        consumer.vertex(pose.pose(), x1 - px, y, z1 - pz).color(r, g, b, a).endVertex();
        consumer.vertex(pose.pose(), x2 - px, y, z2 - pz).color(r, g, b, a).endVertex();
        consumer.vertex(pose.pose(), x2 + px, y, z2 + pz).color(r, g, b, a).endVertex();
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
        consumer.vertex(pose.pose(), x, y, z).color(r, g, b, a).endVertex();
    }
}
