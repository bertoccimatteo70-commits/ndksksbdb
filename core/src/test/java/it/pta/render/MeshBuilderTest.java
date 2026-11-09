package it.pta.render;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MeshBuilderTest {
    @Test
    public void boxUsesItsAbsoluteTopInsteadOfAddingItToTheBaseAgain() {
        MeshBuilder b = new MeshBuilder();
        b.box(0f, 5f, 0f, 1f, 2f, 1f, 1f, 1f, 1f, Atlas.FACADE, 0.5f);
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < b.vertexCount; i++) {
            float y = b.data[i * MeshBuilder.FLOATS_PER_VERTEX + 1];
            min = Math.min(min, y);
            max = Math.max(max, y);
        }
        assertEquals("bottom", 3f, min, 0.0001f);
        assertEquals("top", 7f, max, 0.0001f);
    }
}
