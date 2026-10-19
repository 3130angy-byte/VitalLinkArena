// Uso (desde la carpeta del proyecto):
//   java tools/GenerarColiseo.java src/main/resources/battle/coliseo.png [vista.png]
//
// Fondo del 1 vs 1 en PIXEL ART (pedido del usuario, 2026-10-05; reemplaza a "Battle coliseum.png" en
// las Batallas aleatoria y Oficial). El fondo anterior era una ilustración en alta resolución con
// degradados y los Digimon son pixel art de 64x56: no encajaban. Ahora toda la escena vive en una
// cuadrícula de 480x270 (16:9) y se agranda x2, x3... con vecino más cercano, así el coliseo, el HUD,
// los ataques y los Digimon tienen píxeles del mismo tamaño. Los marcadores (nombre, vida, atributo)
// los dibuja BattlePresentationScreen encima, en las esquinas de arriba (aquí hay cielo).
// Coliseo visto desde un costado: gradas como elipses concéntricas con público y pasillos, la puerta
// al fondo, el muro con una franja de luz y la arena de piedra con anillos. "vista.png" = x2.
import java.awt.image.*; import javax.imageio.*; import java.io.*;
/** Dibuja el fondo del coliseo del 1 vs 1 (480x270). */
public class GenerarColiseo {
  static final int W = 480, H = 270;
  // Paleta: azules de noche (como la sala y el aura de evolución), luz cian y lámparas cálidas.
  static final int P0 = 0x070B1F, P1 = 0x0C1433, P2 = 0x121D4A, P3 = 0x18275F, P4 = 0x203277, P5 = 0x2A4091,
      P6 = 0x3552AD, P7 = 0x4468C8, P8 = 0x5A86E0, P9 = 0x7FB0F0, P10 = 0xB8DCFF;
  static final int C1 = 0x5CD6FF, C2 = 0xC9F6FF, L1 = 0xFFD34A, L2 = 0xFF9A2E, L3 = 0xFFF2BE;
  /** Público: tonos apagados (están en la penumbra) y uno claro para la cabeza. */
  static final int[][] CROWD = {{0x4B3A78, 0x6E5AA0}, {0x6A4636, 0x93654C}, {0x3D5E48, 0x5C8566},
      {0x7A6030, 0xA88A48}, {0x405684, 0x6178B0}, {0x6E3646, 0x99505F}, {0x2F4F6E, 0x4A7298}};

  /** Gradas: elipses concéntricas (centro abajo, fuera de cuadro) entre el muro (s=0) y el borde de arriba (s=1). */
  static final double CX = 240, CY = 210, IN_RX = 236, IN_RY = 60, OUT_RX = 330, OUT_RY = 175;
  static final int ROWS = 9;
  /** Arena: elipse de piedra donde pelean (los pies de los Digimon van en y = 226). */
  static final double FCY = 214, F_RX = 228, F_RY = 52;
  /** Pasillos de las gradas (ángulo; pi/2 = al fondo, en el centro, donde está la puerta). */
  static final double[] AISLES = {Math.PI / 2 - 0.32, Math.PI / 2 + 0.32, Math.PI / 2 - 0.68, Math.PI / 2 + 0.68,
      Math.PI / 2 - 1.05, Math.PI / 2 + 1.05};
  static final int[][] BAYER = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};

  static final int[] px = new int[W * H];

  public static void main(String[] a) throws Exception {
    sky();
    stands();
    wall();
    gate();
    floor();
    torches();
    BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
    img.setRGB(0, 0, W, H, px, 0, W);
    File out = new File(a[0]);
    out.getAbsoluteFile().getParentFile().mkdirs();
    ImageIO.write(img, "png", out);
    if (a.length > 1) {
      BufferedImage big = new BufferedImage(W * 2, H * 2, BufferedImage.TYPE_INT_RGB);
      for (int y = 0; y < H * 2; y++) for (int x = 0; x < W * 2; x++) big.setRGB(x, y, px[(y / 2) * W + x / 2]);
      ImageIO.write(big, "png", new File(a[1]));
    }
    System.out.println("Listo: " + W + "x" + H + " -> " + a[0]);
  }

  // ================================================================ capas

  /** Cielo de noche con degradado tramado y algunas estrellas. */
  static void sky() {
    int[] ramp = {P0, P1, P2, P3};
    for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
      set(x, y, shade(ramp, Math.min(1, y / 90.0), x, y));
      int h = hash(x, y * 7 + 3);
      if (y < 80 && h % 400 == 0) set(x, y, h % 3 == 0 ? P10 : P8);
    }
  }

  /** Gradas: filas de asientos con público, escalones claros, pasillos y el borde de arriba. */
  static void stands() {
    for (int y = 0; y < (int) CY; y++) for (int x = 0; x < W; x++) {
      double s = standParam(x, y);
      if (s < 0 || s > 1.07) continue;
      double rx = lerp(IN_RX, OUT_RX, s), ry = lerp(IN_RY, OUT_RY, s);
      double th = Math.atan2((CY - y) / ry, (x - CX) / rx);
      double light = 1 - 0.38 * Math.min(1, s) - 0.3 * Math.abs(Math.cos(th));
      if (s > 1) { // parapeto del borde de arriba
        set(x, y, s > 1.055 ? P8 : dither(light, P4, P6, x, y));
        continue;
      }
      double rowF = s * ROWS;
      int row = (int) rowF;
      double f = rowF - row;
      double aisleX = Double.NaN;
      for (double a : AISLES) {
        double ax = CX + rx * Math.cos(a);
        double hw = 2.4 - 1.0 * s;
        if (Math.abs(x - ax) < hw && y < CY - ry * Math.sin(a) + 3 && y > CY - ry * Math.sin(a) - 3) aisleX = ax;
      }
      if (!Double.isNaN(aisleX)) {
        set(x, y, f < 0.25 ? P7 : light > 0.55 ? P6 : P5);
        continue;
      }
      if (f < 0.18) { set(x, y, light > 0.6 ? P7 : P6); continue; }       // canto del escalón
      int seat = row % 2 == 0 ? (light > 0.5 ? P5 : P4) : (light > 0.5 ? P4 : P3);
      set(x, y, seat);
      if (f > 0.28 && f < 0.86) {                                           // público
        double u = (Math.PI - th) * rx / 3.0;
        int person = (int) Math.floor(u), col = (int) Math.floor((u - person) * 3);
        int h = hash(person, row * 31 + 5);
        if (h % 10 < 6 && col < 2) {
          int[] c = CROWD[h / 10 % CROWD.length];
          boolean head = f < 0.44 && col == (h / 70 % 2);
          // Apagados hacia el color del asiento: el público es textura, los Digimon son lo importante.
          set(x, y, mix(head ? c[1] : c[0], seat, light < 0.42 ? 0.6 : 0.42));
        }
      }
    }
  }

  /** s tal que (x, y) cae en la elipse de las gradas de parámetro s (0 = muro, 1 = borde); -1 si no hay. */
  static double standParam(int x, int y) {
    double px0 = x + 0.5, py0 = y + 0.5;
    if (f(px0, py0, 0) < 0) return -1;      // dentro del muro (arena)
    if (f(px0, py0, 1.07) > 0) return 2;    // fuera de todo (cielo)
    double lo = 0, hi = 1.07;
    for (int i = 0; i < 24; i++) {
      double mid = (lo + hi) / 2;
      if (f(px0, py0, mid) > 0) lo = mid; else hi = mid;
    }
    return (lo + hi) / 2;
  }

  static double f(double x, double y, double s) {
    double rx = lerp(IN_RX, OUT_RX, s), ry = lerp(IN_RY, OUT_RY, s);
    return sq((x - CX) / rx) + sq((y - CY) / ry) - 1;
  }

  /** Muro bajo alrededor de la arena: tapa clara, paneles y una franja de luz cian. */
  static void wall() {
    for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
      double ei = sq((x + 0.5 - CX) / IN_RX) + sq((y + 0.5 - CY) / IN_RY);
      double ef = sq((x + 0.5 - CX) / F_RX) + sq((y + 0.5 - FCY) / F_RY);
      if (ei >= 1 || ef <= 1 || y > FCY) continue;
      double top = CY - IN_RY * Math.sqrt(Math.max(0, 1 - sq((x + 0.5 - CX) / IN_RX)));
      double bottom = FCY - F_RY * Math.sqrt(Math.max(0, 1 - sq((x + 0.5 - CX) / F_RX)));
      double t = (y + 0.5 - top) / Math.max(1, bottom - top);
      int c = t < 0.12 ? P8 : P3;
      if (Math.abs(t - 0.55) < 0.12) c = C1;
      else if (Math.abs(t - 0.55) < 0.26) c = dither(0.5, P3, P6, x, y);
      if ((x + 7) % 22 == 0 && t > 0.12) c = P2;
      set(x, y, c);
    }
  }

  /** Puerta de entrada al fondo (arco oscuro con marco brillante) y un emblema encima. */
  static void gate() {
    double gx = CX, base = CY - IN_RY - 0.5, halfW = 17, rectTop = base - 18, archR = 17;
    for (int y = (int) (rectTop - archR - 3); y <= base; y++) for (int x = (int) (gx - halfW - 3); x <= gx + halfW + 3; x++) {
      double dx = x + 0.5 - gx;
      boolean inArch = y + 0.5 >= rectTop ? Math.abs(dx) <= halfW : Math.hypot(dx, y + 0.5 - rectTop) <= archR;
      boolean inFrame = y + 0.5 >= rectTop ? Math.abs(dx) <= halfW + 3 : Math.hypot(dx, y + 0.5 - rectTop) <= archR + 3;
      if (!inFrame) continue;
      if (!inArch) { set(x, y, Math.abs(dx) > halfW + 1.5 || (y + 0.5 < rectTop && Math.hypot(dx, y + 0.5 - rectTop) > archR + 1.5) ? P8 : C1); continue; }
      double depth = (base - y) / (base - rectTop + archR);
      set(x, y, depth < 0.18 ? dither(0.5, P1, C1, x, y) : depth < 0.3 ? P2 : P0);
    }
    // Emblema: rombo cian sobre la puerta.
    int ey = (int) (rectTop - archR - 10);
    for (int dy = -5; dy <= 5; dy++) for (int dx = -5; dx <= 5; dx++) {
      int d = Math.abs(dx) + Math.abs(dy);
      if (d <= 5) set((int) gx + dx, ey + dy, d == 5 ? P8 : d <= 1 ? C2 : C1);
    }
  }

  /** Arena de piedra: luz en el centro, anillos, junturas radiales y el canto de la losa adelante. */
  static void floor() {
    for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
      double r = floorR(x + 0.5, y + 0.5);
      if (r > 1) {
        double edge = FCY + F_RY * Math.sqrt(Math.max(0, 1 - sq((x + 0.5 - CX) / F_RX)));
        if (y > FCY && Math.abs(x + 0.5 - CX) < F_RX && y + 0.5 - edge < 7) set(x, y, y + 0.5 - edge < 1.5 ? P7 : P2);
        else if (y > FCY) set(x, y, dither(0.35, P0, P1, x, y));
        continue;
      }
      double light = 1 - 0.62 * Math.pow(r, 1.4) + (y > FCY ? 0.05 : -0.05);
      int c = shade(new int[]{P3, P4, P5, P6, P7}, light, x, y);
      double phi = Math.atan2((y + 0.5 - FCY) / F_RY, (x + 0.5 - CX) / F_RX);
      if (r > 0.42 && r < 0.93 && crosses(x, y, (xx, yy) -> Math.floor(angle(xx, yy) / (Math.PI / 8)))) c = mix(c, P2, 0.45);
      if (crossesRing(x, y, 0.18)) c = C1;
      else if (crossesRing(x, y, 0.42) || crossesRing(x, y, 0.93)) c = P8;
      else if (crossesRing(x, y, 0.72)) c = P7;
      if (r < 0.18 && r > 0.12) c = dither(0.5, c, C1, x, y);
      set(x, y, c);
    }
  }

  static double floorR(double x, double y) {
    return Math.sqrt(sq((x - CX) / F_RX) + sq((y - FCY) / F_RY));
  }

  static double angle(double x, double y) {
    return Math.atan2((y - FCY) / F_RY, (x - CX) / F_RX) + Math.PI;
  }

  interface Field { double at(double x, double y); }

  /** Línea de 1 píxel: el valor cambia entre este píxel y el de la derecha o el de abajo. */
  static boolean crosses(int x, int y, Field fld) {
    double v = fld.at(x + 0.5, y + 0.5);
    return v != fld.at(x + 1.5, y + 0.5) || v != fld.at(x + 0.5, y + 1.5);
  }

  static boolean crossesRing(int x, int y, double ring) {
    return crosses(x, y, (xx, yy) -> floorR(xx, yy) < ring ? 0 : 1);
  }

  /** Dos antorchas cálidas a los lados de la puerta (con su halo tramado). Arriba no hay lámparas: ahí van los marcadores. */
  static void torches() {
    for (int side = -1; side <= 1; side += 2) {
      int bx = (int) CX + side * 30, by = (int) (CY - IN_RY) - 1;
      for (int dy = -6; dy <= 6; dy++) for (int dx = -7; dx <= 7; dx++) {
        double d = Math.hypot(dx, dy * 1.3);
        if (d < 7 && d > 2.5 && (BAYER[(by - 18 + dy) & 3][(bx + dx) & 3] < (7 - d) * 2)) set(bx + dx, by - 18 + dy, mix(get(bx + dx, by - 18 + dy), L3, 0.35));
      }
      for (int y = by - 15; y <= by; y++) { set(bx, y, P6); set(bx + 1, y, P4); }
      for (int dx = -2; dx <= 2; dx++) { set(bx + dx, by - 16, P3); set(bx + dx, by - 19, L2); }
      for (int dx = -2; dx <= 2; dx++) { set(bx + dx, by - 18, L1); set(bx + dx, by - 17, L1); }
      set(bx, by - 18, L3);
      set(bx - 1, by - 18, L3);
    }
  }

  // ================================================================ utilidades

  static int shade(int[] ramp, double t, int x, int y) {
    t = Math.max(0, Math.min(1, t)) * (ramp.length - 1);
    int lo = (int) Math.floor(t);
    if (lo >= ramp.length - 1) return ramp[ramp.length - 1];
    return dither(t - lo, ramp[lo], ramp[lo + 1], x, y);
  }

  /** Tramado ordenado 4x4: con "t" de 0 a 1, cuántos píxeles toman el color b. */
  static int dither(double t, int a, int b, int x, int y) {
    return t * 16 > BAYER[y & 3][x & 3] + 0.5 ? b : a;
  }

  static int mix(int a, int b, double t) {
    int r = (int) Math.round((a >> 16 & 255) * (1 - t) + (b >> 16 & 255) * t);
    int g = (int) Math.round((a >> 8 & 255) * (1 - t) + (b >> 8 & 255) * t);
    int bl = (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
    return r << 16 | g << 8 | bl;
  }

  static void set(int x, int y, int c) { if (x >= 0 && y >= 0 && x < W && y < H) px[y * W + x] = c; }
  static int get(int x, int y) { return x >= 0 && y >= 0 && x < W && y < H ? px[y * W + x] : 0; }
  static double sq(double v) { return v * v; }
  static double lerp(double a, double b, double t) { return a + (b - a) * t; }

  static int hash(int a, int b) {
    int h = a * 0x27d4eb2d ^ b * 0x165667b1;
    h ^= h >>> 15; h *= 0x85ebca6b; h ^= h >>> 13; h *= 0xc2b2ae35; h ^= h >>> 16;
    return h & 0x7fffffff;
  }
}
