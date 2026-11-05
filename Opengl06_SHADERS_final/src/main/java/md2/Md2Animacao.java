package md2;

// Uma animação do MD2 é um intervalo de frames com o mesmo prefixo de nome (ex: "run1".."run6").
public class Md2Animacao {
	public String nome;
	public int inicio;
	public int fim;

	public Md2Animacao(String nome, int inicio, int fim) {
		this.nome = nome;
		this.inicio = inicio;
		this.fim = fim;
	}

	@Override
	public String toString() {
		return nome + " [" + inicio + "-" + fim + "]";
	}
}
