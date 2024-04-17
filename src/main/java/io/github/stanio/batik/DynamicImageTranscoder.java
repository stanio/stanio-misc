/*
 * SPDX-FileCopyrightText: 2023 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.batik;

// java.base
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

// java.xml
import org.w3c.dom.Document;
import org.w3c.dom.Element;

// java.desktop
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

// batik-util
import org.apache.batik.util.ParsedURL;
import org.apache.batik.util.SVGConstants;
// batik-awt-util
import org.apache.batik.ext.awt.image.GraphicsUtil;
// batik-gvt
import org.apache.batik.gvt.CanvasGraphicsNode;
import org.apache.batik.gvt.renderer.ConcreteImageRendererFactory;
import org.apache.batik.gvt.renderer.ImageRenderer;
import org.apache.batik.gvt.renderer.ImageRendererFactory;
// batik-bridge
import org.apache.batik.bridge.BridgeContext;
import org.apache.batik.bridge.UpdateManager;
import org.apache.batik.bridge.UpdateManagerAdapter;
import org.apache.batik.bridge.UpdateManagerEvent;
import org.apache.batik.bridge.ViewBox;
// batik-transcoder
import org.apache.batik.transcoder.SVGAbstractTranscoder;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.TranscodingHints;
import org.apache.batik.transcoder.image.ImageTranscoder;

/**
 * Allows for transcoding dynamic updates to the same document.  Streamlines
 * the transcoding of different output configurations (f.e. sizes) of the same
 * document, reusing loaded document instance.
 *
 * <h2>Usage</h2>
 * <pre>
 * <code>        DynamicImageTranscoder transcoder = new DynamicImageTranscoder();
 *
 *         TranscoderInput input;
 *         TranscoderOutput output;
 *         ...
 *
 *         transcoder.loadDocument(input)
 *                   .transcodeTo(output);
 *
 *         // Equivlent to standard:
 *
 *         transcoder.transcode(input, output);</code></pre>
 * <p>
 * One may further manipulate the loaded document and save the result:</p>
 * <pre>
 * <code>        transcoder.updateContext(ctx -> ctx.getDocument()
 *                                            .getElementById("...")
 *                                            .setAttribute("fill", "red"))
 *                   .transcodeTo(output);</code></pre>
 * <p>
 * Or better yet, with dynamic context (the default):</p>
 * <pre>
 * <code>        transcoder.transcodeDynamic(output,
 *                 ctx -> ctx.getDocument().getElementById("...")
 *                                         .setAttribute("fill", "red"));</code></pre>
 * <p>
 * The examples further refer to {@code fileInput(String)} and
 * {@code fileOutput(int)} methods with the following definitions:</p>
 * <pre>
 * <code>    static TranscoderInput fileInput(String path) {
 *         return new TranscoderInput(Path.of(path).toUri().toString());
 *     }
 *
 *     static OutputStream fileOutput(int num) throws IOException {
 *         Path file = Path.of(String.format("frame-%03d.png", num));
 *         return Files.newOutputStream(file);
 *     }</code></pre>
 *
 * <h3>Frames of animation</h3>
 * <pre>
 * <code>        transcoder.loadDocument(fileInput("animated.svg"));
 *
 *         final float duration  = 3;  // seconds
 *         final float frameRate = 20; // per second
 *
 *         int frameNum = 1;
 *         for (float snapshotTime = 0;
 *                 snapshotTime &lt; duration;
 *                 snapshotTime = frameNum++ / frameRate)
 *         {
 *             final float currentTime = snapshotTime;
 *
 *             try (OutputStream fout = fileOutput(frameNum)) {
 *                 transcoder.transcodeDynamic(new TranscoderOutput(fout),
 *                      ctx -> ctx.getAnimationEngine()
 *                                .setCurrentTime(currentTime));
 *             }
 *         }</code></pre>
 *
 * <h3>DOM manipulation</h3>
 * <p>
 * The output dimensions remain the same:</p>
 * <pre>
 * <code>        transcoder.loadDocument(fileInput("static.svg"));
 *
 *         int variant = 1;
 *         for (int size : new int[] { 200, 300, 400 }) {
 *             String viewBox = "0 0 " + size + " " + size;
 *
 *             try (OutputStream fout = fileOutput(variant++)) {
 *                 transcoder.transcodeDynamic(new TranscoderOutput(fout), ctx -> {
 *                     ctx.getDocument().getDocumentElement()
 *                                      .setAttribute("viewBox", viewBox));
 *                 });
 *             }
 *         }</code></pre>
 *
 * <h3>Different output sizes</h3>
 * <p>
 * Changing the output size after {@code loadDocument()} should be followed by
 * {@code resetView()}:</p>
 * <pre>
 * <code>        transcoder.loadDocument(fileInput("icon.svg"));
 *
 *         int variant = 1;
 *         for (int size : new int[] { 96, 64, 48, 32 }) {
 *             transcoder.withImageWidth(size).resetView();
 *
 *             try (OutputStream fout = fileOutput(variant++)) {
 *                 transcoder.transcodeTo(new TranscoderOutput(fout));
 *             }
 *         }</code></pre>
 *
 * @see  <a href="https://cwiki.apache.org/confluence/display/XMLGRAPHICSBATIK/DynamicSvgOffscreen"
 *              >DynamicSvgOffscreen</a> <i>(Based on)</i>
 * @see  ImageTranscoder
 *
 * @min.jdk  1.8
 */
public class DynamicImageTranscoder extends SVGAbstractTranscoder {

    private static final String MSG_NULL_CTX =
            "Bridge context is not initialized. Call loadDocument() first";

    private static final String MSG_NON_DYN_CTX =
            "Bridge context is not dynamic. Use withDynamicContext(true)";

    private static AffineTransform identityTx = new AffineTransform();

    protected ImageTranscoder imageWriter;

    private UpdateListener updateListener;

    private ImageWriter pngWriter;

    private BridgeContext savedContext;

    private boolean initOnly;

    public DynamicImageTranscoder() {
        hints.put(KEY_EXECUTE_ONLOAD, true);
    }

    public DynamicImageTranscoder(ImageTranscoder imageWriter) {
        this();
        imageWriter.setTranscodingHints(hints);
        this.imageWriter = imageWriter;
    }

    @Override
    public void setTranscodingHints(TranscodingHints hints) {
        super.setTranscodingHints(hints);
        if (imageWriter != null)
            imageWriter.setTranscodingHints(hints);
    }

    /**
     * Specify if new document context should be dynamic.  Should be called
     * before {@link #loadDocument(TranscoderInput)}.  This transcoder uses
     * dynamic context by default.
     *
     * @param   dynamic  ...
     * @return  this transcoder
     * @see     BridgeContext#isDynamic()
     * @see     SVGAbstractTranscoder#KEY_EXECUTE_ONLOAD
     */
    public DynamicImageTranscoder withDynamicContext(boolean dynamic) {
        addTranscodingHint(KEY_EXECUTE_ONLOAD, dynamic);
        return this;
    }

    /**
     * Sets the output image width.  Invoke {@code resetView()} when called
     * after {@link #loadDocument(TranscoderInput)}.
     *
     * @param   width  ...
     * @return  this transcoder
     * @see     SVGAbstractTranscoder#KEY_WIDTH
     * @see     #resetView()
     */
    public DynamicImageTranscoder withImageWidth(int width) {
        if (width <= 0) {
            removeTranscodingHint(KEY_WIDTH);
        } else {
            addTranscodingHint(KEY_WIDTH, (float) width);
        }
        return this;
    }

    /**
     * Sets the output image height.  Invoke {@code resetView()} when called
     * after {@link #loadDocument(TranscoderInput)}.
     *
     * @param   height  ...
     * @return  this transcoder
     * @see     SVGAbstractTranscoder#KEY_HEIGHT
     * @see     #resetView()
     */
    public DynamicImageTranscoder withImageHeight(int height) {
        if (height <= 0) {
            removeTranscodingHint(KEY_HEIGHT);
        } else {
            addTranscodingHint(KEY_HEIGHT, (float) height);
        }
        return this;
    }

    public DynamicImageTranscoder withDocument(Document document)
            throws TranscoderException {
        TranscoderInput input = new TranscoderInput(document);
        input.setURI(document.getDocumentURI());
        return loadDocument(input);
    }

    public DynamicImageTranscoder
            loadDocument(TranscoderInput input) throws TranscoderException {
        initOnly = true;
        try {
            transcode(input, new RenderedTranscoderOutput());
        } finally {
            initOnly = false;
        }
        return this;
    }

    private BridgeContext bridgeContext() {
        if (ctx == null) {
            throw new IllegalStateException(MSG_NULL_CTX);
        }
        return ctx;
    }

    public void transcodeTo(TranscoderOutput output) throws TranscoderException {
        if (bridgeContext().isDynamic()) {
            updateContext(ctx -> {
                /* Ensure updates have completed */
                //ctx.getUpdateManager().forceRepaint();
            });
        }
        paintImageTo(output);
    }

    @Override
    public void transcode(TranscoderInput input,
                          TranscoderOutput output)
            throws TranscoderException
    {
        clean();
        super.transcode(input, output);
        ctx = savedContext; // resurrect
        savedContext = null;
    }

    @Override
    protected void transcode(Document document,
                             String uri,
                             TranscoderOutput output)
            throws TranscoderException
    {
        // Set up ctx, root, curTxf & curAOI
        super.transcode(document, uri, output);

        savedContext = ctx;
        if (initOnly) {
            // Prevent SVGAbstractTranscoder from disposing the ctx
            ctx = null;
            return;
        }

        paintImageTo(output);
    }

    /**
     * Call this after changing the output image dimensions.
     * <p>
     * If the current context is not {@linkplain #withDynamicContext(boolean)
     * dynamic} this will rebuild the GVT root so any {@linkplain
     * #updateContext(Consumer) updates to the document} get reflected in
     * the output.</p>
     *
     * @return  this transcoder
     * @throws  TranscoderException   if an error occurs while transcoding
     * @see     #withImageWidth(int)
     * @see     #withImageHeight(int)
     */
    public DynamicImageTranscoder resetView() throws TranscoderException {
        Document svgDoc = bridgeContext().getDocument();
        if (ctx.isDynamic()) {
            updateContext(ctx -> resetCurTxf());
            return this;
        }

        initOnly = true;
        try {
            // Rebuild the ctx and GVT root
            clean();
            transcode(svgDoc, svgDoc.getDocumentURI(),
                      new RenderedTranscoderOutput());
            ctx = savedContext;
            savedContext = null; // clean up
        } finally {
            initOnly = false;
        }
        return this;
    }

    protected void resetCurTxf() {
        /* Copied from SVGAbstractTranscoder.transcode(Document, ...) */

        String uri = ctx.getDocument().getDocumentURI();

        if (hints.containsKey(KEY_WIDTH))
            width = (float) hints.get(KEY_WIDTH);
        if (hints.containsKey(KEY_HEIGHT))
            height = (float) hints.get(KEY_HEIGHT);

        Element svgRoot = ctx.getDocument().getDocumentElement();

        // get the 'width' and 'height' attributes of the SVG document
        float docWidth = (float) ctx.getDocumentSize().getWidth();
        float docHeight = (float) ctx.getDocumentSize().getHeight();

        setImageSize(docWidth, docHeight);

        // compute the preserveAspectRatio matrix
        AffineTransform Px;

        // take the AOI into account if any
        if (hints.containsKey(KEY_AOI)) {
            Rectangle2D aoi = (Rectangle2D) hints.get(KEY_AOI);
            // transform the AOI into the image's coordinate system
            Px = new AffineTransform();
            double sx = width / aoi.getWidth();
            double sy = height / aoi.getHeight();
            double scale = Math.min(sx, sy);
            Px.scale(scale, scale);
            double tx = -aoi.getX() + (width / scale - aoi.getWidth()) / 2;
            double ty = -aoi.getY() + (height / scale - aoi.getHeight()) / 2;
            Px.translate(tx, ty);
            // take the AOI transformation matrix into account
            // we apply first the preserveAspectRatio matrix
            curAOI = aoi;
        } else {
            String ref = new ParsedURL(uri).getRef();

            // XXX Update this to use the animated value of 'viewBox' and
            //     'preserveAspectRatio'.
            String viewBox = svgRoot.getAttribute(SVGConstants.SVG_VIEW_BOX_ATTRIBUTE);

            if (ref != null && !ref.trim().isEmpty()) {
                Px = ViewBox.getViewTransform(ref, svgRoot, width, height, ctx);
            } else if (viewBox != null && !viewBox.trim().isEmpty()) {
                String aspectRatio = svgRoot.getAttribute(
                        SVGConstants.SVG_PRESERVE_ASPECT_RATIO_ATTRIBUTE);
                Px = ViewBox.getPreserveAspectRatioTransform(
                        svgRoot, viewBox, aspectRatio, width, height, ctx);
            } else {
                // no viewBox has been specified, create a scale transform
                float xscale, yscale;
                xscale = width / docWidth;
                yscale = height / docHeight;
                float scale = Math.min(xscale, yscale);
                Px = AffineTransform.getScaleInstance(scale, scale);
            }

            curAOI = new Rectangle2D.Float(0, 0, width, height);
        }

        CanvasGraphicsNode canvas = getCanvasGraphicsNode(this.root);
        if (canvas == null) {
            curTxf = Px;
        } else {
            canvas.setViewingTransform(Px);
            curTxf = new AffineTransform();
        }

        UpdateManager updateManager = ctx.getUpdateManager();
        if (updateManager == null) return;

        //RepaintManager repaintManager = updateManager.getRepaintManager();
        //repaintManager.setupRenderer(curTxf, false,
        //        curAOI, (int) (width + 0.5), (int) (height + 0.5));
        updateManager.updateRendering(curTxf, false,
                curAOI, (int) (width + 0.5), (int) (height + 0.5));
    } // resetCurTxf() : void

    private void paintImageTo(TranscoderOutput output) throws TranscoderException {
        try {
            writeImage(paintImage(), output);
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            if (cause == null || e.getClass() != RuntimeException.class) {
                throw e;
            }
            throw new TranscoderException(e.getMessage(), (Exception) cause);
        } catch (Exception e) {
            throw new TranscoderException(e);
        }
    }

    BufferedImage paintImage() {
        return imageWithBkg(initImageRenderer().getOffScreen());
    }

    BufferedImage imageWithBkg(BufferedImage image) {
        return imageWithBkg(image, (int) (width + 0.5f),
                                   (int) (height + 0.5f));
    }

    private BufferedImage imageWithBkg(BufferedImage image, int w, int h) {
        /* Copied from ImageTranscoder.transcode(Document, ...) */

        BufferedImage dest = createImage(w, h);
        Graphics2D g2d = GraphicsUtil.createGraphics(dest);
        if (hints.containsKey(ImageTranscoder.KEY_BACKGROUND_COLOR)) {
            Paint bgcolor = (Paint)
                    hints.get(ImageTranscoder.KEY_BACKGROUND_COLOR);
            g2d.setComposite(AlphaComposite.SrcOver);
            g2d.setPaint(bgcolor);
            g2d.fillRect(0, 0, w, h);
        }
        g2d.drawRenderedImage(image, identityTx);
        g2d.dispose();
        return dest;
    }

    // REVISIT: Reuse renderer for static documents, and in general.
    ImageRenderer initImageRenderer() {
        /* Copied from ImageTranscoder.transcode(Document, ...) */

        ImageRenderer renderer = createRenderer();

        int w = (int) (width + 0.5);
        int h = (int) (height + 0.5);

        renderer.updateOffScreen(w, h);
        renderer.setTransform(curTxf);
        renderer.setTree(root);

        Shape raoi = new Rectangle2D.Float(0, 0, width, height);
        try {
            // Warning: the renderer's AOI must be in user space
            renderer.repaint(curTxf.createInverse()
                                   .createTransformedShape(raoi));
        } catch (NoninvertibleTransformException e) {
            throw new RuntimeException(e);
        }
        return renderer;
    }

    //@Override
    protected ImageRenderer createRenderer() {
        ImageRendererFactory factory = new ConcreteImageRendererFactory();
        ImageRenderer renderer = factory.createDynamicImageRenderer();
        renderer.setDoubleBuffered(false);
        RenderingHints renderingHints = renderer.getRenderingHints();
        renderingHints.put(RenderingHints.KEY_RENDERING,
                           RenderingHints.VALUE_RENDER_QUALITY);
        renderingHints.put(RenderingHints.KEY_STROKE_CONTROL,
                           RenderingHints.VALUE_STROKE_PURE);
        renderingHints.put(RenderingHints.KEY_FRACTIONALMETRICS,
                           RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        //savedRoot = root;
        return renderer;
    }

    public DynamicImageTranscoder
            updateDocument(Consumer<? super Document> update) {
        return fromContext(ctx -> {
            update.accept(ctx.getDocument());
            return this;
        });
    }

    public DynamicImageTranscoder
            updateContext(Consumer<? super BridgeContext> update) {
        return fromContext(ctx -> {
            update.accept(ctx);
            return this;
        });
    }

    public <T> T fromDocument(Function<? super Document, ? extends T> func) {
        return fromContext(ctx -> func.apply(ctx.getDocument()));
    }

    public <T> T fromContext(Function<? super BridgeContext, ? extends T> func) {
        if (!bridgeContext().isDynamic()) {
            return func.apply(ctx);
        }

        UpdateManager manager = getUpdateManager();
        AtomicReference<T> retVal = updateListener.retVal();
        try {
            manager.getUpdateRunnableQueue()
                    .invokeAndWait(() -> retVal.set(func.apply(ctx)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted", e);
        }
        return retVal.get();
    }

    protected UpdateManager getUpdateManager() {
        UpdateManager updateManager = bridgeContext().getUpdateManager();
        if (updateManager == null) {
            if (!ctx.isDynamic())
                throw new IllegalStateException(MSG_NON_DYN_CTX);

            updateManager = new UpdateManager(ctx, root, ctx.getDocument());
            updateManager.setMinRepaintTime(-1);

            // Done in SVGAbstractTranscoder.transcode(Document, ...)
            //
            //try {
            //    manager.dispatchSVGLoadEvent();
            //} catch (InterruptedException e) {
            //    Thread.currentThread().interrupt();
            //    throw new TranscoderException("Interrupted", e);
            //}

            updateListener = new UpdateListener();
            updateManager.addUpdateManagerListener(updateListener);
            updateManager.manageUpdates(initImageRenderer());
        }
        return updateManager;
    }

    public void transcodeDynamic(TranscoderOutput output,
                                 Consumer<? super BridgeContext> update)
            throws TranscoderException
    {
        BufferedImage image = transcodeDynamic(update);
        if (image != null)
            writeImage(image, output);
    }

    public BufferedImage transcodeDynamic(Consumer<? super BridgeContext> update)
            throws TranscoderException
    {
        updateListener.syncImageUpdate(() -> {
            updateContext(ctx -> {
                updateListener.setAwaitImage();
                update.accept(ctx);
            });
        });

        if (updateListener.failure()) {
            throw new TranscoderException("image update failed");
        }

        BufferedImage image = updateListener.image();
        if (image == null) {
            throw new TranscoderException("image not updated");
        }
        return image;
    }

    //@Override
    protected BufferedImage createImage(int width, int height) {
        return (imageWriter == null)
                ? new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                : imageWriter.createImage(width, height);
    }

    //@Override
    protected void writeImage(BufferedImage image, TranscoderOutput output)
            throws TranscoderException
    {
        if (output instanceof RenderedTranscoderOutput) {
            ((RenderedTranscoderOutput) output).setImage(image);
        } else if (imageWriter == null) {
            writePNG(image, output);
        } else {
            imageWriter.writeImage(image, output);
        }
    }

    private void writePNG(BufferedImage image, TranscoderOutput output)
            throws TranscoderException {
        // Use Java Image I/O API (and implementation)
        ImageWriter writer = pngWriter();
        try (OutputStream byteOut = outputStreamFor(output);
                ImageOutputStream out = imageOutputStreamFor(image, byteOut)) {
            writer.setOutput(out);
            writer.write(image);
        } catch (IOException e) {
            throw new TranscoderException(e);
        } finally {
            //writer.reset();
            writer.setOutput(null);
        }
    }

    private ImageWriter pngWriter() {
        ImageWriter writer = pngWriter;
        if (writer == null) {
            Iterator<ImageWriter> iter = ImageIO.getImageWritersByFormatName("png");
            if (iter.hasNext()) {
                writer = iter.next();
                pngWriter = writer;
            } else {
                throw new IllegalStateException("No registered PNG image writer available");
            }
        }
        return writer;
    }

    private static OutputStream outputStreamFor(TranscoderOutput output)
            throws TranscoderException, IOException {
        OutputStream stream = output.getOutputStream();
        if (stream != null)
            return stream;

        final String message = "Invalid output. Dynamic image transcoder"
                + " supports only a byte stream, file: URI, or rendered output";
        if (output.getURI() == null)
            throw new TranscoderException(message);

        try {
            URI uri = URI.create(output.getURI());
            return Files.newOutputStream(Paths.get(uri));
        } catch (IllegalArgumentException | FileSystemNotFoundException e) {
            throw new TranscoderException(message, e);
        }
    }

    private static ImageOutputStream
            imageOutputStreamFor(RenderedImage image, OutputStream stream)
            throws IOException, TranscoderException {
        // Encode smaller images in memory
        return (image.getWidth() * image.getHeight() < 100_000)
                ? new MemoryCacheImageOutputStream(stream)
                : ImageIO.createImageOutputStream(stream);
    }

    public void clean() {
        root = null;
        if (ctx != null) {
            UpdateManager manager = ctx.getUpdateManager();
            if (manager != null) {
                manager.dispatchSVGUnLoadEvent();
                updateListener.awaitShutdown();
                updateListener = null;
            } else {
                ctx.dispose();
            }
            ctx = null;
        }
    }

    public static TranscoderInput fileInput(Path file) {
        return new TranscoderInput(file.toUri().toString());
    }

    public static TranscoderOutput fileOutput(Path file) {
        return new TranscoderOutput(file.toUri().toString());
    }


    private class UpdateListener extends UpdateManagerAdapter {

        private final AtomicReference<BufferedImage> image = new AtomicReference<>();
        private volatile boolean failure;
        private volatile AtomicBoolean awaitImage = new AtomicBoolean();

        private final CountDownLatch shutdownLatch = new CountDownLatch(1);

        private final AtomicReference<?> retVal = new AtomicReference<>();

        private final long timeoutMillis;

        UpdateListener() {
            this.timeoutMillis = TimeUnit.SECONDS.toMillis(Integer
                    .getInteger("io.github.stanio.batik.updateListenerTimeoutSeconds", 5));
        }

        void setAwaitImage() {
            awaitImage.set(true);
        }

        @Override public void updateCompleted(UpdateManagerEvent evt) {
            if (!awaitImage.get()) return;

            synchronized (this) {
                try {
                    image.set(imageWithBkg(evt.getImage()));
                } finally {
                    awaitImage.set(false);
                    notify();
                }
            }
        }

        @Override public void updateFailed(UpdateManagerEvent evt) {
            if (!awaitImage.get()) return;

            synchronized (this) {
                failure = true;
                awaitImage.set(false);
                notify();
            }
        }

        @Override public void managerStopped(UpdateManagerEvent e) {
            shutdownLatch.countDown();
        }

        synchronized void syncImageUpdate(Runnable task) throws TranscoderException {
            try {
                image.set(null);
                failure = false;
                awaitImage.set(false); // set await from within the update thread,
                                       // should be included in the given task
                task.run();

                if (waitWhile(timeoutMillis, awaitImage::get))
                    return;

                throw new TranscoderException("image update timed out");

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted", e);
            }
        }

        private boolean waitWhile(long timeoutMillis,
                                  BooleanSupplier condition)
                throws InterruptedException
        {
            long currentTime = System.currentTimeMillis();
            long endTime = currentTime + timeoutMillis;
            while (endTime > currentTime && condition.getAsBoolean()) {
                wait(endTime - currentTime);
                currentTime = System.currentTimeMillis();
            }
            return !condition.getAsBoolean();
        }

        void awaitShutdown() {
            try {
                if (!shutdownLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    throw new RuntimeException("timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted", e);
            }
        }

        boolean failure() {
            return failure;
        }

        BufferedImage image() {
            return image.getAndSet(null);
        }

        @SuppressWarnings("unchecked")
        <T> AtomicReference<T> retVal() {
            retVal.set(null);
            return (AtomicReference<T>) retVal;
        }

    } // class UpdateListener


    public static class RenderedTranscoderOutput extends TranscoderOutput {

        private BufferedImage image;

        public void setImage(BufferedImage image) {
            this.image = image;
        }

        public BufferedImage getImage() {
            return image;
        }

    } // class RenderedTranscoderOutput


} // class DynamicImageTranscoder
