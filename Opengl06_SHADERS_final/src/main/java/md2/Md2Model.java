package md2;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;

import org.lwjgl.BufferUtils;

import Model.Model;
import shaders.ShaderProgram;

// Modelo MD2 (Quake 2) renderizado com shader.
// Todos os keyframes (posições e normais) vão para a GPU uma única vez no load().
// No draw() só escolhemos quais dois frames ligar nos atributos do shader,
// e o vertex shader faz a interpolação entre eles.
public class Md2Model extends Model {
	static final int MD2_IDENT = 844121161; // "IDP2" lido como int little-endian
	static final int MD2_VERSAO = 8;

	public int skinwidth, skinheight;
	public int numFrames;
	public int nvertex = 0; // vértices desenhados (3 por triângulo)
	public String[] nomesFrames;
	public ArrayList<Md2Animacao> animacoes = new ArrayList<Md2Animacao>();

	// Frames em sequência num único array: o frame f começa no float f * nvertex * 3
	float[] posicoes;
	float[] normais;
	float[] uvs;

	// Bounding box do frame 0 (coordenadas do MD2), usada no Main para centralizar e escalar
	public float[] bboxMin = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE };
	public float[] bboxMax = { -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };

	int vbo_vertex_handle;
	int vbo_normal_handle;
	int vbo_textcood_handle;
	ShaderProgram shader;

	// Estado da animação
	public int animAtual = 0;
	public int frameAtual = 0;
	public int proximoFrame = 0;
	public float t = 0; // fração (0..1) entre frameAtual e proximoFrame
	public float framesPorSegundo = 9;
	public boolean interpolar = true;

	public Md2Model(String arquivo, ShaderProgram shader) throws IOException {
		this.shader = shader;
		loadMd2(arquivo);
		separarAnimacoes();
		load();
		setAnimacao(0);
	}

	// Lê o arquivo binário MD2 (little-endian) e monta os arrays de posições, normais e UVs.
	public void loadMd2(String arquivo) throws IOException {
		ByteBuffer buf = ByteBuffer.wrap(Files.readAllBytes(Paths.get(arquivo))).order(ByteOrder.LITTLE_ENDIAN);

		if (buf.getInt(0) != MD2_IDENT || buf.getInt(4) != MD2_VERSAO) {
			throw new IOException("Arquivo nao e um MD2 valido: " + arquivo);
		}

		skinwidth = buf.getInt(8);
		skinheight = buf.getInt(12);
		int tamanhoFrame = buf.getInt(16);
		int numVertices = buf.getInt(24);
		int numSt = buf.getInt(28);
		int numTris = buf.getInt(32);
		numFrames = buf.getInt(40);
		int ofsSt = buf.getInt(48);
		int ofsTris = buf.getInt(52);
		int ofsFrames = buf.getInt(56);

		// Triângulos: 3 índices de vértice + 3 índices de coordenada de textura (shorts sem sinal)
		nvertex = numTris * 3;
		int[] indVertice = new int[nvertex];
		uvs = new float[nvertex * 2];
		for (int i = 0; i < numTris; i++) {
			int pos = ofsTris + i * 12;
			for (int k = 0; k < 3; k++) {
				indVertice[i * 3 + k] = buf.getShort(pos + k * 2) & 0xFFFF;
				int st = buf.getShort(pos + 6 + k * 2) & 0xFFFF;
				// O MD2 guarda a UV em pixels da skin; o OpenGL quer 0..1
				uvs[(i * 3 + k) * 2] = (float) buf.getShort(ofsSt + st * 4) / skinwidth;
				uvs[(i * 3 + k) * 2 + 1] = (float) buf.getShort(ofsSt + st * 4 + 2) / skinheight;
			}
		}

		// Cada frame: scale[3], translate[3], nome[16] e os vértices compactados em 4 bytes
		// (x, y, z, índice da normal na tabela Anorms)
		posicoes = new float[numFrames * nvertex * 3];
		normais = new float[numFrames * nvertex * 3];
		nomesFrames = new String[numFrames];
		float[] verticesFrame = new float[numVertices * 3];
		float[] normaisFrame = new float[numVertices * 3];

		for (int f = 0; f < numFrames; f++) {
			int pos = ofsFrames + f * tamanhoFrame;
			float[] escala = { buf.getFloat(pos), buf.getFloat(pos + 4), buf.getFloat(pos + 8) };
			float[] translacao = { buf.getFloat(pos + 12), buf.getFloat(pos + 16), buf.getFloat(pos + 20) };
			nomesFrames[f] = lerString(buf, pos + 24, 16);

			int posVertices = pos + 40;
			for (int v = 0; v < numVertices; v++) {
				for (int c = 0; c < 3; c++) {
					int valor = buf.get(posVertices + v * 4 + c) & 0xFF;
					verticesFrame[v * 3 + c] = valor * escala[c] + translacao[c];
				}
				int indNormal = buf.get(posVertices + v * 4 + 3) & 0xFF;
				float[] n = Anorms.NORMAIS[Math.min(indNormal, Anorms.NORMAIS.length - 1)];
				// A tabela do Quake está girada 90° em Z em relação aos vértices: usando (-y, x, z)
				// a normal coincide com a calculada pela geometria (testado nos MD2 do pacote).
				// O Quake 2 não percebe isso porque ilumina por tabelas indexadas pelo ângulo do modelo.
				normaisFrame[v * 3] = -n[1];
				normaisFrame[v * 3 + 1] = n[0];
				normaisFrame[v * 3 + 2] = n[2];

				if (f == 0) {
					atualizaBoundingBox(verticesFrame, v);
				}
			}

			// Expande para a ordem dos triângulos (3 vértices por triângulo), assim o draw é um glDrawArrays direto, sem índices
			int inicio = f * nvertex * 3;
			for (int i = 0; i < nvertex; i++) {
				for (int c = 0; c < 3; c++) {
					posicoes[inicio + i * 3 + c] = verticesFrame[indVertice[i] * 3 + c];
					normais[inicio + i * 3 + c] = normaisFrame[indVertice[i] * 3 + c];
				}
			}
		}

		System.out.println("MD2 " + arquivo + ": " + numVertices + " vertices, " + numTris + " triangulos, "
				+ numFrames + " frames, skin " + skinwidth + "x" + skinheight);
	}

	// Nome do frame: string de tamanho fixo terminada em '\0'
	String lerString(ByteBuffer buf, int pos, int tamanho) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < tamanho; i++) {
			byte b = buf.get(pos + i);
			if (b == 0) {
				break;
			}
			sb.append((char) b);
		}
		return sb.toString();
	}

	void atualizaBoundingBox(float[] vertices, int v) {
		for (int c = 0; c < 3; c++) {
			bboxMin[c] = Math.min(bboxMin[c], vertices[v * 3 + c]);
			bboxMax[c] = Math.max(bboxMax[c], vertices[v * 3 + c]);
		}
	}

	// Agrupa frames consecutivos cujo nome sem os dígitos finais é igual ("stand01", "stand02" -> "stand").
	// Se a numeração pular ("pain104" -> "pain201"), começa outra animação com o mesmo nome ("pain2").
	void separarAnimacoes() {
		animacoes.clear();
		HashMap<String, Integer> repeticoes = new HashMap<String, Integer>();
		String baseAnterior = null;
		int numeroAnterior = -1;

		for (int i = 0; i < nomesFrames.length; i++) {
			String base = nomesFrames[i].replaceAll("[0-9]+$", "");
			String digitos = nomesFrames[i].substring(base.length());
			int numero = (digitos.isEmpty() || digitos.length() > 9) ? -1 : Integer.parseInt(digitos);

			boolean continua = base.equals(baseAnterior) && (numero < 0 || numero == numeroAnterior + 1);
			if (continua) {
				animacoes.get(animacoes.size() - 1).fim = i;
			} else {
				int n = repeticoes.getOrDefault(base, 0) + 1;
				repeticoes.put(base, n);
				animacoes.add(new Md2Animacao(n == 1 ? base : base + n, i, i));
			}
			baseAnterior = base;
			numeroAnterior = numero;
		}
	}

	// Cria um VBO para posições, um para normais e um para UVs, com todos os frames.
	// São GL_STATIC_DRAW: nada é reenviado durante a animação, só mudamos o offset no draw().
	@Override
	public void load() {
		vbo_vertex_handle = criaVbo(posicoes);
		vbo_normal_handle = criaVbo(normais);
		vbo_textcood_handle = criaVbo(uvs);
	}

	int criaVbo(float[] dados) {
		FloatBuffer buffer = BufferUtils.createFloatBuffer(dados.length);
		buffer.put(dados);
		buffer.flip();

		int handle = glGenBuffers();
		glBindBuffer(GL_ARRAY_BUFFER, handle);
		glBufferData(GL_ARRAY_BUFFER, buffer, GL_STATIC_DRAW);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		return handle;
	}

	public void setAnimacao(int indice) {
		int n = animacoes.size();
		animAtual = ((indice % n) + n) % n; // permite índice negativo para voltar
		frameAtual = animacoes.get(animAtual).inicio;
		proximoFrame = seguinte(frameAtual);
		t = 0;
	}

	public Md2Animacao getAnimacao() {
		return animacoes.get(animAtual);
	}

	// Próximo frame dentro da animação atual, voltando ao início no fim (loop).
	int seguinte(int frame) {
		Md2Animacao a = animacoes.get(animAtual);
		return frame >= a.fim ? a.inicio : frame + 1;
	}

	// Avança o tempo da animação em dt segundos.
	public void atualizar(double dt) {
		t += dt * framesPorSegundo;
		while (t >= 1) {
			t -= 1;
			frameAtual = proximoFrame;
			proximoFrame = seguinte(frameAtual);
		}
	}

	@Override
	public void draw() {
		// Deslocamento em bytes do início de cada frame dentro dos VBOs
		long offsetFrame1 = (long) frameAtual * nvertex * 3 * 4;
		long offsetFrame2 = (long) proximoFrame * nvertex * 3 * 4;

		// Posições: att 0 = frame atual, att 4 = próximo frame (mesmo VBO, offsets diferentes)
		glBindBuffer(GL_ARRAY_BUFFER, vbo_vertex_handle);
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, offsetFrame1);
		glEnableVertexAttribArray(4);
		glVertexAttribPointer(4, 3, GL_FLOAT, false, 0, offsetFrame2);

		// Normais: att 2 = frame atual, att 5 = próximo frame
		glBindBuffer(GL_ARRAY_BUFFER, vbo_normal_handle);
		glEnableVertexAttribArray(2);
		glVertexAttribPointer(2, 3, GL_FLOAT, false, 0, offsetFrame1);
		glEnableVertexAttribArray(5);
		glVertexAttribPointer(5, 3, GL_FLOAT, false, 0, offsetFrame2);

		// UVs são iguais em todos os frames
		glBindBuffer(GL_ARRAY_BUFFER, vbo_textcood_handle);
		glEnableVertexAttribArray(3);
		glVertexAttribPointer(3, 2, GL_FLOAT, false, 0, 0);

		// Fator de interpolação; com 0 o shader mostra só o keyframe atual
		int locInterp = glGetUniformLocation(shader.programID, "interp");
		glUniform1f(locInterp, interpolar ? t : 0);

		glDrawArrays(GL_TRIANGLES, 0, nvertex);

		glDisableVertexAttribArray(0);
		glDisableVertexAttribArray(2);
		glDisableVertexAttribArray(3);
		glDisableVertexAttribArray(4);
		glDisableVertexAttribArray(5);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}
}
