package shaders;

// Shader usado pelo MD2: recebe dois keyframes (posição + normal de cada) e interpola no vertex shader.
public class StaticShader extends ShaderProgram {

	private static final String VERTEX_FILE = "./shaders/vertexShader.txt";
	private static final String FRAGMENT_FILE = "./shaders/fragmentShader.txt";

	public StaticShader() {
		super(VERTEX_FILE, FRAGMENT_FILE);
	}

	@Override
	protected void bindAttributes() {
		super.bindAttribute(0, "posFrame1");
		super.bindAttribute(2, "normalFrame1");
		super.bindAttribute(3, "textuv");
		super.bindAttribute(4, "posFrame2");
		super.bindAttribute(5, "normalFrame2");
	}
}
