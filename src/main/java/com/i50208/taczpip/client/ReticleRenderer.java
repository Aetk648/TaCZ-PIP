package com.i50208.taczpip.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.Arrays;

/** 准星顶点快照：只收集数字，不使用 TaCZ / Iris 的共享渲染批次。 */
public final class ReticleRenderer implements VertexConsumer {
    private final Matrix4f mvp;
    private float[] data = new float[256];
    private int count;
    private final Vector4f position = new Vector4f();

    public ReticleRenderer(Matrix4f projection, Matrix4f modelView) {
        mvp = new Matrix4f(projection).mul(modelView);
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        if (data.length < (count + 1) * 10) data = Arrays.copyOf(data, data.length * 2);
        position.set(x, y, z, 1).mul(mvp);
        int offset = count++ * 10;
        data[offset] = position.x;
        data[offset + 1] = position.y;
        data[offset + 2] = 0; // 最终合成不参与世界深度测试
        data[offset + 3] = position.w;
        Arrays.fill(data, offset + 6, offset + 10, 1);
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        int offset = (count - 1) * 10 + 6;
        data[offset] = r / 255F;
        data[offset + 1] = g / 255F;
        data[offset + 2] = b / 255F;
        data[offset + 3] = a / 255F;
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        int offset = (count - 1) * 10 + 4;
        data[offset] = u;
        data[offset + 1] = v;
        return this;
    }

    @Override public VertexConsumer setUv1(int u, int v) { return this; }
    @Override public VertexConsumer setUv2(int u, int v) { return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }

    /**
     * Bedrock 模型输出四边形；独立合成器使用核心 OpenGL 支持的三角形。
     * 只取完整的四边形：顶点数不是 4 的倍数时丢掉尾巴，而不是整份丢弃 ——
     * 整份丢弃会让"捕获成功→原版分划被跳过→又没有顶点可画"，准星整个消失。
     */
    public float[] triangles() {
        int quads = count / 4;
        if (quads == 0) return new float[0];
        float[] triangles = new float[quads * 6 * 10];
        int[] indices = {0, 1, 2, 0, 2, 3};
        int out = 0;
        for (int quad = 0; quad < quads * 4; quad += 4) {
            for (int index : indices) {
                System.arraycopy(data, (quad + index) * 10, triangles, out, 10);
                out += 10;
            }
        }
        for (float value : triangles) if (!Float.isFinite(value)) return new float[0];
        return triangles;
    }
}
