import com.sun.net.httpserver.HttpServer;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Ring colour switcher as a web page, with the recolouring done in Java.
 * Run:  java RingWebServer.java      then open http://localhost:8080
 * Needs ring.jpg in the same folder. No libraries required.
 */
public class RingWebServer {

    // key -> {display name, swatch CSS colour, ramp stops {brightness,R,G,B}...}; ramp null = original gold
    record Metal(String key, String name, String css, double[][] ramp) {}

    static final List<Metal> METALS = List.of(
        new Metal("gold", "Yellow gold", "#d9a21b", null),
        new Metal("rose", "Rose gold", "#d9917f", new double[][]{
            {0, 52, 22, 18}, {.35, 170, 88, 76}, {.65, 224, 160, 140}, {.9, 250, 218, 206}, {1, 255, 242, 235}}),
        new Metal("silver", "Silver", "#c3c8d0", new double[][]{
            {0, 34, 37, 43}, {.4, 141, 147, 155}, {.75, 214, 218, 224}, {1, 255, 255, 255}}),
        new Metal("black", "Black rhodium", "#2c2f35", new double[][]{
            {0, 8, 9, 11}, {.45, 52, 55, 62}, {.8, 118, 123, 133}, {1, 225, 228, 235}}));

    static BufferedImage original;
    static float[] mask, bright;
    static final Map<String, byte[]> cache = new HashMap<>();

    static double smooth(double a, double b, double x) {
        double t = Math.min(1, Math.max(0, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    // Decide which pixels are metal (saturated + gold-ish hue); skips white background and the diamond
    static void buildMask() {
        int w = original.getWidth(), h = original.getHeight();
        mask = new float[w * h];
        bright = new float[w * h];
        float[] hsb = new float[3];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int rgb = original.getRGB(x, y), r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
                Color.RGBtoHSB(r, g, b, hsb);
                double hue = hsb[0] * 360;
                double hw = (hue > 20 && hue < 62) ? 1 : smooth(8, 20, hue) * smooth(70, 62, hue);
                int i = y * w + x;
                mask[i] = (float) (smooth(.10, .28, hsb[1]) * hw);
                double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
                bright[i] = (float) Math.min(1, Math.max(0, (lum - .12) / .85));
            }
    }

    static double[] ramp(double[][] s, double t) {
        for (int i = 1; i < s.length; i++)
            if (t <= s[i][0]) {
                double[] p = s[i - 1], q = s[i];
                double k = (t - p[0]) / (q[0] - p[0]);
                return new double[]{p[1] + (q[1] - p[1]) * k, p[2] + (q[2] - p[2]) * k, p[3] + (q[3] - p[3]) * k};
            }
        double[] l = s[s.length - 1];
        return new double[]{l[1], l[2], l[3]};
    }

    // Recolour from the original pixels, touching only metal pixels; returns PNG bytes
    static byte[] render(Metal m) throws IOException {
        int w = original.getWidth(), h = original.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int rgb = original.getRGB(x, y), i = y * w + x;
                float k = mask[i];
                if (m.ramp() == null || k == 0) { out.setRGB(x, y, rgb); continue; }
                double[] c = ramp(m.ramp(), bright[i]);
                int r = (int) (((rgb >> 16) & 255) * (1 - k) + c[0] * k);
                int g = (int) (((rgb >> 8) & 255) * (1 - k) + c[1] * k);
                int b = (int) ((rgb & 255) * (1 - k) + c[2] * k);
                out.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(out, "png", bos);
        return bos.toByteArray();
    }

    static String page() {
        StringBuilder btn = new StringBuilder();
        for (Metal m : METALS)
            btn.append("<button data-k=\"%s\" data-n=\"%s\"><i style=\"background:%s\"></i>%s</button>"
                .formatted(m.key(), m.name(), m.css(), m.name()));
        return """
<!DOCTYPE html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>Ring colour switcher</title>
<style>
body{margin:0;min-height:100vh;display:grid;place-items:center;background:#e8ebf0;font-family:Georgia,serif;color:#1c2230}
main{width:min(420px,92vw)}
.stage{background:#fff;padding:12px;border-radius:6px;box-shadow:0 18px 40px -22px rgba(20,25,40,.45)}
img{width:100%;display:block}
h1{font-weight:500;margin:20px 0 2px}#n{margin:0 0 16px;color:#5d6678;font-style:italic}
.sw{display:flex;gap:14px;flex-wrap:wrap}
button{all:unset;cursor:pointer;display:grid;justify-items:center;gap:6px;font-size:.9rem;color:#5d6678}
button i{width:44px;height:44px;border-radius:50%;box-shadow:inset 0 0 0 1px rgba(0,0,0,.2);outline:2px solid transparent;outline-offset:3px}
button.on{color:#1c2230}button.on i{outline-color:#1c2230}
</style></head><body><main>
<div class="stage"><img id="ring" src="/ring?metal=gold" alt="Solitaire ring"></div>
<h1>Solitaire ring</h1><p id="n">Yellow gold</p>
<div class="sw">%s</div></main>
<script>
const bs=document.querySelectorAll("button");bs[0].classList.add("on");
bs.forEach(b=>b.onclick=()=>{bs.forEach(x=>x.classList.remove("on"));b.classList.add("on");
 document.getElementById("n").textContent=b.dataset.n;
 document.getElementById("ring").src="/ring?metal="+b.dataset.k;});
</script></body></html>
""".replace("%s", btn.toString());
    }

    public static void main(String[] args) throws Exception {
        original = ImageIO.read(new File("ring.jpg"));
        buildMask();
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/", ex -> {                       // the web page
            byte[] body = page().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });

        server.createContext("/ring", ex -> {                   // the recoloured image
            String q = Optional.ofNullable(ex.getRequestURI().getQuery()).orElse("metal=gold");
            String key = q.startsWith("metal=") ? q.substring(6) : "gold";
            Metal m = METALS.stream().filter(x -> x.key().equals(key)).findFirst().orElse(METALS.get(0));
            byte[] png;
            synchronized (cache) {
                png = cache.get(m.key());
                if (png == null) { png = render(m); cache.put(m.key(), png); }
            }
            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.sendResponseHeaders(200, png.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(png); }
        });

        server.start();
        System.out.println("Open http://localhost:8080");
    }
}
