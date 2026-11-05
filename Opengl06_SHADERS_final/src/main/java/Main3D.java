import org.lwjgl.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Vector3f;

import md2.Md2Model;
import shaders.StaticShader;
import util.TextureLoader;

import java.io.IOException;
import java.nio.*;

import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL20.glGetUniformLocation;
import static org.lwjgl.opengl.GL20.glUniform1i;
import static org.lwjgl.opengl.GL20.glUniform3f;
import static org.lwjgl.opengl.GL20.glUniformMatrix4fv;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;

public class Main3D {

	// The window handle
	private long window;

	float viewAngX = 0;
	float viewAngY = 0;
	float scale = 1.0f;
	boolean mostrarNormais = false;
	boolean girarModelo = true;
	boolean girarLuz = false;

	StaticShader shader;
	Md2Model personagem;
	Md2Model arma;

	public void run() throws IOException {
		System.out.println("Hello LWJGL " + Version.getVersion() + "!");

		init();
		loop();

		// Free the window callbacks and destroy the window
		glfwFreeCallbacks(window);
		glfwDestroyWindow(window);

		// Terminate GLFW and free the error callback
		glfwTerminate();
		glfwSetErrorCallback(null).free();
	}

	private void init() {
		GLFWErrorCallback.createPrint(System.err).set();

		if (!glfwInit())
			throw new IllegalStateException("Unable to initialize GLFW");

		glfwDefaultWindowHints();
		glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
		glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

		window = glfwCreateWindow(1500, 1000, "MD2 com Shaders", NULL, NULL);
		if (window == NULL)
			throw new RuntimeException("Failed to create the GLFW window");

		glfwSetKeyCallback(window, (window, key, scancode, action, mods) -> {
			if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE)
				glfwSetWindowShouldClose(window, true);

			// Câmera: W/S giram em X, A/D giram em Y, Z/X dão zoom (mudam a escala da view)
			if (key == GLFW_KEY_W) {
				viewAngX += 5;
			}
			if (key == GLFW_KEY_S) {
				viewAngX -= 5;
			}
			if (key == GLFW_KEY_A) {
				viewAngY += 5;
			}
			if (key == GLFW_KEY_D) {
				viewAngY -= 5;
			}
			if (key == GLFW_KEY_Z) {
				scale = scale * 1.1f;
			}
			if (key == GLFW_KEY_X) {
				scale = scale * 0.9f;
			}

			// Controles do MD2: só no momento em que a tecla é pressionada
			if (action == GLFW_PRESS) {
				if (key == GLFW_KEY_RIGHT) {
					personagem.setAnimacao(personagem.animAtual + 1);
				}
				if (key == GLFW_KEY_LEFT) {
					personagem.setAnimacao(personagem.animAtual - 1);
				}
				if (key == GLFW_KEY_I) {
					personagem.interpolar = !personagem.interpolar;
				}
				if (key == GLFW_KEY_N) {
					mostrarNormais = !mostrarNormais;
				}
				if (key == GLFW_KEY_R) {
					girarModelo = !girarModelo;
				}
				if (key == GLFW_KEY_L) {
					girarLuz = !girarLuz;
				}
			}
		});

		try (MemoryStack stack = stackPush()) {
			IntBuffer pWidth = stack.mallocInt(1);
			IntBuffer pHeight = stack.mallocInt(1);
			glfwGetWindowSize(window, pWidth, pHeight);
			GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
			glfwSetWindowPos(window, (vidmode.width() - pWidth.get(0)) / 2, (vidmode.height() - pHeight.get(0)) / 2);
		}

		glfwMakeContextCurrent(window);
		glfwSwapInterval(1);
		glfwShowWindow(window);
	}

	private void loop() throws IOException {
		GL.createCapabilities();

		shader = new StaticShader();

		personagem = new Md2Model("md2/tris.md2", shader);
		arma = new Md2Model("md2/weapon.md2", shader);
		int texPersonagem = TextureLoader.loadTexture(TextureLoader.loadImage("md2/steve.png"));
		int texArma = TextureLoader.loadTexture(TextureLoader.loadImage("md2/weapon.png"));

		System.out.println("Animacoes: " + personagem.animacoes);
		System.out.println("Controles: <- -> animacao | I interpolacao | N normais | R girar modelo | L girar luz | W A S D camera | Z X zoom | ESC sai");

		// Centraliza o modelo e escala para ocupar ~1.4 de altura no espaço de recorte.
		// No MD2 o eixo Z é a altura.
		float[] centro = new float[3];
		for (int c = 0; c < 3; c++) {
			centro[c] = (personagem.bboxMin[c] + personagem.bboxMax[c]) / 2;
		}
		float escalaModelo = 1.4f / (personagem.bboxMax[2] - personagem.bboxMin[2]);

		int locModel = glGetUniformLocation(shader.programID, "model");
		int locView = glGetUniformLocation(shader.programID, "view");
		int locTex = glGetUniformLocation(shader.programID, "tex");
		int locLuz = glGetUniformLocation(shader.programID, "luzDir");
		int locMostrarNormais = glGetUniformLocation(shader.programID, "mostrarNormais");

		glEnable(GL_DEPTH_TEST);
		glClearColor(0.3f, 0.3f, 0.35f, 1.0f);

		FloatBuffer matrixBuffer = MemoryUtil.memAllocFloat(16);
		int[] largura = new int[1], altura = new int[1];
		float angModelo = -1.5708f; // o MD2 olha para +X; girando -90° ele começa de frente para a câmera
		float angLuz = 0.9f; // luz começa à direita e acima; vinda de frente, ela ilumina tudo por igual
		int frame = 0;
		long lasttime = System.currentTimeMillis();
		long ultimoTempo = System.currentTimeMillis();

		while (!glfwWindowShouldClose(window)) {

			long diftime = System.currentTimeMillis() - ultimoTempo;
			ultimoTempo = System.currentTimeMillis();

			personagem.atualizar(diftime / 1000.0);
			if (girarModelo) {
				angModelo += 0.6f * diftime / 1000.0f;
			}
			if (girarLuz) {
				angLuz += 1.5f * diftime / 1000.0f;
			}

			glfwSetWindowTitle(window, "MD2 com Shaders | animacao: " + personagem.getAnimacao().nome
					+ " | interpolacao " + (personagem.interpolar ? "ON" : "OFF")
					+ (mostrarNormais ? " | normais" : "") + (girarLuz ? " | luz girando" : ""));

			glfwGetFramebufferSize(window, largura, altura);
			if (altura[0] == 0) { // janela minimizada
				glfwPollEvents();
				continue;
			}
			glViewport(0, 0, largura[0], altura[0]);
			float aspecto = (float) largura[0] / altura[0];

			glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

			shader.start();

			glActiveTexture(GL_TEXTURE0);
			glUniform1i(locTex, 0);
			// Luz girando em volta do eixo Y (no plano XZ), sempre um pouco de cima
			glUniform3f(locLuz, (float) Math.sin(angLuz), 0.6f, (float) Math.cos(angLuz));
			glUniform1i(locMostrarNormais, mostrarNormais ? 1 : 0);

			// View: escala (zoom) e rotação da câmera; o x é dividido pelo aspecto para o modelo não esticar
			Matrix4f view = new Matrix4f();
			view.setIdentity();
			view.scale(new Vector3f(scale / aspecto, scale, scale));
			view.rotate(viewAngX * 0.0174532f, new Vector3f(1, 0, 0));
			view.rotate(viewAngY * 0.0174532f, new Vector3f(0, 1, 0));
			view.storeTranspose(matrixBuffer);
			matrixBuffer.flip();
			glUniformMatrix4fv(locView, false, matrixBuffer);

			// Model: gira em Y, converte Z-para-cima (MD2) em Y-para-cima e centraliza na origem
			Matrix4f modelm = new Matrix4f();
			modelm.setIdentity();
			modelm.rotate(angModelo, new Vector3f(0, 1, 0));
			modelm.rotate(-90 * 0.0174532f, new Vector3f(1, 0, 0));
			modelm.scale(new Vector3f(escalaModelo, escalaModelo, escalaModelo));
			modelm.translate(new Vector3f(-centro[0], -centro[1], -centro[2]));
			modelm.storeTranspose(matrixBuffer);
			matrixBuffer.flip();
			glUniformMatrix4fv(locModel, false, matrixBuffer);

			glBindTexture(GL_TEXTURE_2D, texPersonagem);
			personagem.draw();

			// A arma tem os mesmos frames do corpo, então usa o mesmo par de frames e o mesmo t.
			// Ela não tem os frames de morte; nesses ela não é desenhada.
			if (personagem.frameAtual < arma.numFrames && personagem.proximoFrame < arma.numFrames) {
				arma.frameAtual = personagem.frameAtual;
				arma.proximoFrame = personagem.proximoFrame;
				arma.t = personagem.t;
				arma.interpolar = personagem.interpolar;
				glBindTexture(GL_TEXTURE_2D, texArma);
				arma.draw();
			}

			shader.stop();

			glfwSwapBuffers(window);
			glfwPollEvents();

			frame++;
			long actualTime = System.currentTimeMillis();
			if ((lasttime / 1000) != (actualTime / 1000)) {
				System.out.println("FPS " + frame);
				frame = 0;
				lasttime = actualTime;
			}
		}

		MemoryUtil.memFree(matrixBuffer);
	}

	public static void main(String[] args) throws IOException {
		new Main3D().run();
	}
}
