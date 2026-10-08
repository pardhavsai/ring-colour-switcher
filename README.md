Jewellery Colour Studio

A web app written in Java. Upload a photo of a jewellery piece (ring, bangle, pendant, earring...), choose a metal, and see the piece recoloured. The gemstones and the background stay unchanged. You can then download the result.

Metals offered: yellow gold, rose gold, white gold, silver, platinum and black rhodium.

How it works
Upload: the page sends the photo to the Java server. Large photos are shrunk to 1000 px and transparent PNGs are placed on white.
Background removal: the server flood-fills inward from the image border to find the plain background. Highlights inside the metal are not mistaken for background.
Metal mask: it detects the dominant hue of the piece (gold, rose gold, copper...) and keeps only pixels close to that hue. Gemstones of other colours and clear diamonds are left out.
Gradient map: each metal pixel keeps only its brightness (stretched to the metal's own light and dark range). That brightness is looked up on a colour ramp for the chosen metal, so highlights and reflections still look like shiny metal.
Blend: result = original x (1 - mask) + new colour x mask, which gives soft edges and leaves stones untouched.
Serve: a built-in Java HTTP server (com.sun.net.httpserver) handles /upload (stores the photo and its mask) and /ring (returns the recoloured PNG).
Requirements
JDK 11 or newer (check with javac -version)
No other libraries or build tools
Run it
java JewelleryColourStudio.java

Open http://localhost:8080, choose a photo, pick a metal, and click Download image to save it. Press Ctrl+C in the terminal to stop. If port 8080 is busy, change 8080 in main and use the new port in the browser.

Add or change a metal

In the page script, edit the METALS list: each entry is a name, a base colour in hex, and a swatch colour.

Limitations
The photo needs a plain, single-colour background.
Silver or white-metal pieces cannot be told apart from a diamond by colour, so a stone in a silver piece may also be recoloured.
Pieces made of two metals are treated as one.
Soft shadows under the piece can pick up a faint tint when the source is silver.
Uploaded photos are kept in memory and forgotten when the server restarts.
Files
JewelleryColourStudio.java: the server, the image processing and the web page
