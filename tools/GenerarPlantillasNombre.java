// Uso (desde la carpeta del proyecto): java tools/GenerarPlantillasNombre.java src/main/resources/fonts
// Lee las hojas fonts/VB_Alphabet_ENG_*.png (letras separadas por columnas magenta) y escribe
// fonts/vb_name_fonts.txt, que usa org.example.dim.NameSpriteReader. Para otra fuente: agregarla en main().
import java.awt.image.*; import javax.imageio.*; import java.io.*; import java.util.*; import java.nio.file.*; import java.nio.charset.StandardCharsets;
/** Convierte las hojas de fuente (celdas separadas por columnas magenta) en plantillas de texto. */
public class GenerarPlantillasNombre {
  public static void main(String[] a) throws Exception {
    String dir = a[0]; StringBuilder out = new StringBuilder();
    out.append("# Plantillas de las fuentes del sprite NAME (generadas desde fonts/VB_Alphabet_ENG_*.png).\n");
    out.append("# FONT <nombre> <minusculas: si|no>; GLYPH <simbolo> <ancho>; luego 15 filas ('#' = tinta). ESPACIO = \"SPACE\".\n");
    font(out, dir + "/VB_Alphabet_ENG_DigiScript.png", "DigiScript", true, "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz -=_()1234567890");
    font(out, dir + "/VB_Alphabet_ENG_Official_Bandai.png", "Official_Bandai", false, "ABCDEFGHIJKLMNOPQRSTUVWXYZ -=_()1234567890");
    font(out, dir + "/VB_Alphabet_ENG_Agero.png", "Agero", false, "ABCDEFGHIJKLMNOPQRSTUVWXYZ -=_[]1234567890");
    Files.writeString(Path.of(dir, "vb_name_fonts.txt"), out.toString(), StandardCharsets.UTF_8);
    System.out.print(out.substring(0, Math.min(out.length(), 1200)));
  }
  static void font(StringBuilder out, String file, String name, boolean lower, String symbols) throws Exception {
    BufferedImage b = ImageIO.read(new File(file)); int W = b.getWidth(), H = b.getHeight();
    boolean[] mag = new boolean[W];
    for (int x = 0; x < W; x++) { int m = 0; for (int y = 0; y < H; y++) if (isMag(b.getRGB(x, y))) m++; mag[x] = m >= H - 1; }
    List<int[]> cells = new ArrayList<>(); int s = 0;
    for (int x = 0; x <= W; x++) if (x == W || mag[x]) { if (x > s) cells.add(new int[]{s, x}); s = x + 1; }
    if (cells.size() != symbols.length()) throw new IllegalStateException(name + ": " + cells.size() + " celdas, " + symbols.length() + " símbolos");
    out.append("FONT ").append(name).append(' ').append(lower ? "si" : "no").append('\n');
    for (int i = 0; i < cells.size(); i++) {
      int x0 = cells.get(i)[0], x1 = cells.get(i)[1]; char c = symbols.charAt(i);
      int l = x1, r = x0 - 1;
      for (int x = x0; x < x1; x++) for (int y = 0; y < H; y++) if (ink(b.getRGB(x, y))) { l = Math.min(l, x); r = Math.max(r, x); }
      if (c == ' ' || r < l) { l = x0; r = x1 - 1; } // el espacio: todo el ancho de su celda, sin tinta
      out.append("GLYPH ").append(c == ' ' ? "SPACE" : String.valueOf(c)).append(' ').append(r - l + 1).append('\n');
      for (int y = 0; y < H; y++) { for (int x = l; x <= r; x++) out.append(ink(b.getRGB(x, y)) ? '#' : '.'); out.append('\n'); }
    }
  }
  static boolean isMag(int c) { int R = c >> 16 & 255, G = c >> 8 & 255, B = c & 255; return R > 200 && G < 60 && B > 200; }
  static boolean ink(int c) { int R = c >> 16 & 255, G = c >> 8 & 255, B = c & 255; return R > 200 && G > 200 && B > 200; }
}
