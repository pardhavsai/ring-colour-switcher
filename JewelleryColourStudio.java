import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jewellery Colour Studio: upload a photo of any jewellery piece, pick any colour, see the metal recoloured.
 *
 * Run:  java JewelleryColourStudio.java      then open http://localhost:8080
 *
 * Pipeline per uploaded image:
 *   1. background  - flood fill from the border finds the plain background
 *   2. metal mask  - detect the metal's own hue (gold, rose, copper...) and keep only pixels near it;
 *                    grey metals (silver, platinum) use the whole piece
 *   3. gradient map- keep each pixel's brightness, look it up on a colour ramp built from the chosen colour
 *   4. blend       - result = original * (1 - mask) + new colour * mask
 */
public class JewelleryColourStudio {

    static class Session {
        BufferedImage img;
        float[] mask, bright;
        String kind;                             // what was detected
    }

    static final Map<String, Session> sessions = new ConcurrentHashMap<>();

    // ---------- small helpers ----------
    static double smooth(double a, double b, double x) {
        double t = Math.min(1, Math.max(0, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    static double dist(int a, int b) {
        int dr = ((a >> 16) & 255) - ((b >> 16) & 255), dg = ((a >> 8) & 255) - ((b >> 8) & 255), db = (a & 255) - (b & 255);
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    // ---------- 1. background ----------
    /** 0..1 per pixel: how much is jewellery rather than background. Flood fill from the border, so bright
     *  highlights inside the metal are not mistaken for background. */
    static float[] foreground(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int n = 2 * (w + h), k = 0;
        int[] r = new int[n], g = new int[n], b = new int[n];
        for (int x = 0; x < w; x++) for (int y : new int[]{0, h - 1}) { int c = img.getRGB(x, y); r[k] = (c >> 16) & 255; g[k] = (c >> 8) & 255; b[k++] = c & 255; }
        for (int y = 0; y < h; y++) for (int x : new int[]{0, w - 1}) { int c = img.getRGB(x, y); r[k] = (c >> 16) & 255; g[k] = (c >> 8) & 255; b[k++] = c & 255; }
        Arrays.sort(r); Arrays.sort(g); Arrays.sort(b);
        int bg = (r[n / 2] << 16) | (g[n / 2] << 8) | b[n / 2];            // median border colour = background

        float[] fg = new float[w * h];
        Arrays.fill(fg, 1f);
        boolean[] seen = new boolean[w * h];
        int[] queue = new int[w * h];
        int head = 0, tail = 0;
        for (int x = 0; x < w; x++) for (int y : new int[]{0, h - 1}) if (!seen[y * w + x]) { seen[y * w + x] = true; queue[tail++] = y * w + x; }
        for (int y = 0; y < h; y++) for (int x : new int[]{0, w - 1}) if (!seen[y * w + x]) { seen[y * w + x] = true; queue[tail++] = y * w + x; }
        while (head < tail) {
            int p = queue[head++], x = p % w, y = p / w;
            double d = dist(img.getRGB(x, y), bg);
            if (d >= 90) continue;                                           // reached the jewellery: stop spreading
            fg[p] = (float) smooth(40, 90, d);
            if (x > 0 && !seen[p - 1]) { seen[p - 1] = true; queue[tail++] = p - 1; }
            if (x < w - 1 && !seen[p + 1]) { seen[p + 1] = true; queue[tail++] = p + 1; }
            if (y > 0 && !seen[p - w]) { seen[p - w] = true; queue[tail++] = p - w; }
            if (y < h - 1 && !seen[p + w]) { seen[p + w] = true; queue[tail++] = p + w; }
        }
        for (int p = 0; p < w * h; p++)                                      // enclosed bits of exactly background colour (inside a band)
            if (!seen[p]) fg[p] = (float) smooth(6, 25, dist(img.getRGB(p % w, p / w), bg));
        return fg;
    }

    // ---------- 2. metal mask ----------
    static void buildMask(Session s) {
        BufferedImage img = s.img;
        int w = img.getWidth(), h = img.getHeight();
        float[] fg = foreground(img);
        float[] hue = new float[w * h], sat = new float[w * h], val = new float[w * h];
        float[] hsb = new float[3];
        double cx = 0, cy = 0, fgCount = 0, colourful = 0;
        for (int p = 0; p < w * h; p++) {
            int rgb = img.getRGB(p % w, p / w);
            Color.RGBtoHSB((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, hsb);
            hue[p] = hsb[0] * 360; sat[p] = hsb[1]; val[p] = hsb[2];
            if (fg[p] > .9) {
                fgCount++;
                if (sat[p] > .25 && val[p] > .15) {
                    colourful++;
                    cx += sat[p] * Math.cos(Math.toRadians(hue[p]));
                    cy += sat[p] * Math.sin(Math.toRadians(hue[p]));
                }
            }
        }
        boolean coloured = colourful > .2 * fgCount;      // is the metal itself coloured (gold, rose gold...)?
        double metalHue = Math.toDegrees(Math.atan2(cy, cx));
        s.kind = coloured ? "coloured metal (hue " + Math.round((metalHue + 360) % 360) + " degrees)" : "grey metal (silver / platinum)";

        s.mask = new float[w * h];
        s.bright = new float[w * h];
        int[] hist = new int[256];
        int count = 0;
        for (int p = 0; p < w * h; p++) {
            double m;
            if (coloured) {
                double dh = Math.abs(((hue[p] - metalHue + 540) % 360) - 180);
                m = fg[p] * smooth(.10, .28, sat[p]) * smooth(40, 25, dh);
            } else m = fg[p];
            s.mask[p] = (float) m;
            int rgb = img.getRGB(p % w, p / w);
            s.bright[p] = (float) ((0.299 * ((rgb >> 16) & 255) + 0.587 * ((rgb >> 8) & 255) + 0.114 * (rgb & 255)) / 255);
            if (m > .5) { hist[Math.round(s.bright[p] * 255)]++; count++; }
        }
        // stretch brightness between the metal's own darkest (2%) and lightest (98%) tones
        int lo = 0, hi = 255, acc = 0;
        for (int i = 0; i < 256; i++) { acc += hist[i]; if (acc >= .02 * count) { lo = i; break; } }
        acc = 0;
        for (int i = 255; i >= 0; i--) { acc += hist[i]; if (acc >= .02 * count) { hi = i; break; } }
        double span = Math.max(20, hi - lo);
        for (int p = 0; p < w * h; p++) s.bright[p] = (float) Math.min(1, Math.max(0, (s.bright[p] * 255 - lo) / span));
    }

    // ---------- 3. colour ramp from any chosen colour ----------
    /** Dark -> base -> highlight ramp for a chosen colour, keeping its hue (256 entries, indexed by brightness). */
    static int[] makeRamp(int base) {
        float[] hsb = Color.RGBtoHSB((base >> 16) & 255, (base >> 8) & 255, base & 255, null);
        float h = hsb[0], s = hsb[1], v = hsb[2];
        double[][] stops = {                         // brightness, saturation, value
            {0, Math.min(1, s * 1.1), v * .18},
            {.35, Math.min(1, s * 1.05), v * .62},
            {.65, s, v},
            {.9, s * .45, v + (1 - v) * .6},
            {1, s * .15, v + (1 - v) * .9}};
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            double t = i / 255.0;
            int j = 1;
            while (j < stops.length - 1 && t > stops[j][0]) j++;
            double k = (t - stops[j - 1][0]) / (stops[j][0] - stops[j - 1][0]);
            double ss = stops[j - 1][1] + (stops[j][1] - stops[j - 1][1]) * k;
            double vv = stops[j - 1][2] + (stops[j][2] - stops[j - 1][2]) * k;
            lut[i] = Color.HSBtoRGB(h, (float) Math.min(1, Math.max(0, ss)), (float) Math.min(1, Math.max(0, vv))) & 0xFFFFFF;
        }
        return lut;
    }

    // ---------- 4. recolour and blend ----------
    static byte[] render(Session s, Integer target) throws IOException {
        BufferedImage img = s.img;
        int w = img.getWidth(), h = img.getHeight();
        BufferedImage out = img;
        if (target != null) {
            int[] lut = makeRamp(target);
            out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int rgb = img.getRGB(x, y), i = y * w + x;
                    float k = s.mask[i];
                    if (k == 0) { out.setRGB(x, y, rgb); continue; }
                    int c = lut[Math.round(s.bright[i] * 255)];
                    int r = (int) (((rgb >> 16) & 255) * (1 - k) + ((c >> 16) & 255) * k);
                    int g = (int) (((rgb >> 8) & 255) * (1 - k) + ((c >> 8) & 255) * k);
                    int b = (int) ((rgb & 255) * (1 - k) + (c & 255) * k);
                    out.setRGB(x, y, (r << 16) | (g << 8) | b);
                }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(out, "png", bos);
        return bos.toByteArray();
    }

    // ---------- upload handling ----------
    /** Decode, flatten transparency onto white, and shrink big photos so recolouring stays fast. */
    static BufferedImage prepare(byte[] bytes) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
        if (src == null) return null;
        double scale = Math.min(1.0, 1000.0 / Math.max(src.getWidth(), src.getHeight()));
        int w = (int) Math.round(src.getWidth() * scale), h = (int) Math.round(src.getHeight() * scale);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q != null) for (String kv : q.split("&")) { String[] p = kv.split("=", 2); m.put(p[0], p.length > 1 ? java.net.URLDecoder.decode(p[1], StandardCharsets.UTF_8) : ""); }
        return m;
    }

    static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    static void json(HttpExchange ex, int code, String body) throws IOException {
        send(ex, code, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    static String info(String id, Session s) {
        return "{\"id\":\"" + id + "\",\"kind\":\"" + s.kind + "\",\"grey\":" + s.kind.startsWith("grey") + "}";
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/", ex -> send(ex, 200, "text/html; charset=utf-8", PAGE.getBytes(StandardCharsets.UTF_8)));

        // POST /upload  (body = image file)  ->  {id, kind}
        server.createContext("/upload", ex -> {
            try {
                byte[] bytes = ex.getRequestBody().readAllBytes();
                BufferedImage img = prepare(bytes);
                if (img == null) { json(ex, 400, "{\"error\":\"That file is not an image. Use JPG, PNG or GIF.\"}"); return; }
                Session s = new Session();
                s.img = img;
                buildMask(s);
                if (sessions.size() > 30) sessions.clear();                  // keep memory bounded
                String id = UUID.randomUUID().toString().substring(0, 8);
                sessions.put(id, s);
                json(ex, 200, info(id, s));
            } catch (Exception e) { json(ex, 500, "{\"error\":\"Could not read that image.\"}"); }
        });

        // GET /ring?id=..&color=orig|RRGGBB  ->  PNG
        server.createContext("/ring", ex -> {
            Map<String, String> q = query(ex);
            Session s = sessions.get(q.get("id"));
            if (s == null) { json(ex, 404, "{\"error\":\"Upload an image first.\"}"); return; }
            String c = q.getOrDefault("color", "orig");
            Integer target = c.equals("orig") ? null : Integer.parseInt(c, 16);
            send(ex, 200, "image/png", render(s, target));
        });

        server.start();
        System.out.println("Open http://localhost:8080");
    }

    // ---------- the web page ----------
    static final String PAGE = """
<!DOCTYPE html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>Jewellery colour studio</title>
<style>
*{box-sizing:border-box}
body{margin:0;min-height:100vh;background:#e8ebf0;font-family:Georgia,serif;color:#1c2230;display:grid;place-items:start center;padding:28px 16px}
main{width:min(460px,100%)}
h1{font-weight:500;margin:0 0 4px}
p.sub{margin:0 0 18px;color:#5d6678}
.drop{display:block;border:2px dashed #9aa3b5;border-radius:8px;padding:18px;text-align:center;cursor:pointer;background:#f4f6f9}
.drop:hover,.drop.over{background:#fff;border-color:#1c2230}
.drop input{display:none}
.stage{background:#fff;padding:12px;border-radius:6px;margin-top:16px;box-shadow:0 18px 40px -22px rgba(20,25,40,.45);display:none}
.stage img{width:100%;display:block}
#status{margin:12px 0 0;color:#5d6678;font-style:italic;min-height:1.3em}
#err{color:#a1281f;margin:8px 0 0}
.panel{display:none;margin-top:16px}
.sw{display:flex;gap:14px;flex-wrap:wrap;align-items:flex-start}
.sw button{all:unset;cursor:pointer;display:grid;justify-items:center;gap:6px;font-size:.9rem;color:#5d6678}
.sw i{display:block;width:44px;height:44px;border-radius:50%;box-shadow:inset 0 0 0 1px rgba(0,0,0,.2);outline:2px solid transparent;outline-offset:3px}
.sw .on{color:#1c2230}.sw .on i{outline-color:#1c2230}
a.dl{display:inline-block;margin-top:18px;font:inherit;font-size:.95rem;padding:8px 16px;border:1px solid #1c2230;border-radius:6px;background:#1c2230;color:#fff;text-decoration:none}
</style></head><body><main>
<h1>Jewellery colour studio</h1>
<p class="sub">Upload a photo of a ring, bangle, pendant or any piece, then pick the metal you want to see.</p>

<label class="drop" id="drop">
  <input type="file" id="file" accept="image/*">
  <strong>Choose a photo</strong> or drop it here<br><small>Works best on a plain, single-colour background</small>
</label>
<p id="err"></p>

<div class="stage" id="stage"><img id="img" alt="Your jewellery"></div>
<p id="status"></p>

<div class="panel" id="panel">
  <div class="sw" id="sw"></div>
  <a class="dl" id="dl" download="recoloured.png">Download image</a>
</div>
</main>
<script>
const $=id=>document.getElementById(id);
const METALS=[["Original","orig","linear-gradient(135deg,#fff,#bbb)"],
 ["Yellow gold","E2B03A","#E2B03A"],["Rose gold","E0A08C","#E0A08C"],["White gold","E4E2DC","#E4E2DC"],
 ["Silver","C8CDD4","#C8CDD4"],["Platinum","9EA4AE","#9EA4AE"],["Black rhodium","3A3D44","#3A3D44"]];
let id=null,color="orig",ver=0;

METALS.forEach(([n,c,bg],i)=>{
  const b=document.createElement("button");
  b.innerHTML='<i style="background:'+bg+'"></i>'+n;
  b.onclick=()=>{color=c;document.querySelectorAll(".sw .on").forEach(x=>x.classList.remove("on"));b.classList.add("on");show();};
  if(i===0)b.classList.add("on");$("sw").appendChild(b);
});
function show(){
  if(!id)return;
  const u="/ring?id="+id+"&color="+color+"&v="+(++ver);
  $("img").src=u;$("dl").href=u;
}
async function upload(file){
  $("err").textContent="";
  if(!file)return;
  $("status").textContent="Analysing your photo...";
  try{
    const r=await fetch("/upload",{method:"POST",body:file});
    const d=await r.json();
    if(!r.ok){$("err").textContent=d.error;$("status").textContent="";return;}
    id=d.id;color="orig";
    document.querySelectorAll(".sw .on").forEach(x=>x.classList.remove("on"));$("sw").firstChild.classList.add("on");
    $("stage").style.display="block";$("panel").style.display="block";
    $("status").textContent="Pick a metal below.";
    show();
  }catch(e){$("err").textContent="Could not upload that file.";$("status").textContent="";}
}
$("file").onchange=e=>upload(e.target.files[0]);
["dragover","dragenter"].forEach(t=>$("drop").addEventListener(t,e=>{e.preventDefault();$("drop").classList.add("over");}));
["dragleave","drop"].forEach(t=>$("drop").addEventListener(t,e=>{e.preventDefault();$("drop").classList.remove("over");}));
$("drop").addEventListener("drop",e=>upload(e.dataTransfer.files[0]));
</script></body></html>
""";
}
