# Ring Colour Switcher

A small web app, written in Java, that shows a gold ring and lets you switch the metal colour (yellow gold, rose gold, silver, black rhodium) with one click. The ring in the page is the same photo each time. The metal is recoloured in Java, and the diamond and background stay unchanged.

## How it works

1. **Mask:** when the server starts, it reads `ring.jpg` and works out which pixels are metal. A pixel counts as metal if it is saturated and has a gold-like hue, which leaves out the white background and the clear diamond.
2. **Gradient map:** for each metal pixel, only its brightness is kept. That brightness is looked up on a colour ramp for the chosen metal (for example, dark brown to pink to pale pink for rose gold). Because brightness is kept, the highlights and reflections still look like shiny metal.
3. **Serve:** a built-in Java HTTP server (`com.sun.net.httpserver`) sends the web page at `/` and the recoloured image at `/ring?metal=rose` (or `gold`, `silver`, `black`). Each colour is rendered once and cached in memory.
4. **Page:** the swatch buttons change the image address, so the browser shows the new colour without reloading the page.

## Requirements

- JDK 11 or newer (check with `javac -version`)
- No other libraries or build tools

## Run it

1. Put `RingWebServer.java` and `ring.jpg` in the same folder.
2. In a terminal, go to that folder and run:

   ```
   java RingWebServer.java
   ```

3. Open <http://localhost:8080> in your browser and click the swatches.
4. Press Ctrl+C in the terminal to stop the server.

If port 8080 is already in use, change `8080` in `RingWebServer.java` to another number such as `8081`.

## Use a different image

Replace `ring.jpg` with your own image (or change the file name in `ImageIO.read(new File("ring.jpg"))`), then restart the server.

For good results the image should show gold or yellow metal on a plain white background. If gold is left behind in the highlights or the background is picked up, adjust the saturation range in `smooth(.10, .28, hsb[1])` and the hue range in `hue > 20 && hue < 62`.

## Add another metal

Add one more entry to the `METALS` list in `RingWebServer.java` with a key, a name, a swatch colour and a few `{brightness, R, G, B}` colour stops.

## Files

- `RingWebServer.java`: the server, the recolouring code and the web page
- `ring.jpg`: the ring photo used by the demo

## Limitations

- The mask is tuned for a gold ring on a white background. A ring that starts out silver or another pale metal will not be detected as metal.
- Metal that reflects other colours (for example, a reddish glow from a gemstone) may be only partly recoloured.
