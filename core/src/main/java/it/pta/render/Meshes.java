package it.pta.render;

import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;

/** Helpers to turn a CPU MeshBuilder into a GPU mesh. Call on the GL thread. */
public final class Meshes {
    public static final VertexAttribute[] ATTRS = {
            new VertexAttribute(VertexAttributes.Usage.Position, 3, ShaderProgram.POSITION_ATTRIBUTE),
            new VertexAttribute(VertexAttributes.Usage.Normal, 3, ShaderProgram.NORMAL_ATTRIBUTE),
            new VertexAttribute(VertexAttributes.Usage.ColorUnpacked, 4, ShaderProgram.COLOR_ATTRIBUTE),
            new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, ShaderProgram.TEXCOORD_ATTRIBUTE + "0")
    };

    public static Mesh from(MeshBuilder mb) {
        if (mb.isEmpty()) {
            return null;
        }
        Mesh m = new Mesh(true, mb.vertexCount, mb.indexCount, ATTRS);
        m.setVertices(mb.compactVertices());
        m.setIndices(mb.compactIndices());
        return m;
    }

    private Meshes() {
    }
}
