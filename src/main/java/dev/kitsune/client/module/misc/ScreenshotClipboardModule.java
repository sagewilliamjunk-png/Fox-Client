package dev.kitsune.client.module.misc;

import dev.kitsune.client.KitsuneClient;
import dev.kitsune.client.hud.NotificationManager;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import net.minecraft.client.Minecraft;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Screenshot → Clipboard. After vanilla saves a screenshot (F2), the PNG is
 * also copied to the OS clipboard so it can be pasted straight into Discord
 * or an image editor.
 *
 * <p>Deliberately mixin-free: instead of hooking the screenshot writer's
 * internals (whose signature shifts between MC versions), the module watches
 * {@code <gameDir>/screenshots/} once every 10 ticks for files newer than the
 * moment it was enabled, waits one poll for the async write to finish
 * (stable file size), and copies via AWT. Windows-first; on platforms where
 * AWT clipboard access fails the module degrades to a warning toast.
 */
public class ScreenshotClipboardModule extends Module {

    private static final int POLL_INTERVAL_TICKS = 10;

    private final BooleanSetting toast = addSetting(new BooleanSetting("Show Toast", true));

    private int tickCounter = 0;
    /** Only screenshots taken after this moment are copied. */
    private long baselineMs = Long.MAX_VALUE;
    /** Newest already-handled (or pre-existing) file mtime. */
    private long lastHandledMtime = 0;
    /** Candidate from the previous poll waiting for its size to stabilise. */
    private File pending = null;
    private long pendingSize = -1;

    public ScreenshotClipboardModule() {
        super("Screenshot Clipboard", "Copies new screenshots to the clipboard", Category.MISC);
    }

    @Override
    protected void onEnable() {
        baselineMs = System.currentTimeMillis();
        lastHandledMtime = baselineMs;
        pending = null;
        tickCounter = 0;
    }

    @Override
    protected void onDisable() {
        pending = null;
    }

    @Override
    public void onTick() {
        if (++tickCounter < POLL_INTERVAL_TICKS) return;
        tickCounter = 0;

        // A pending candidate is copied once its size stops changing — the
        // vanilla screenshot writer runs async, so the first sighting may be
        // a partially-written file.
        if (pending != null) {
            long size = pending.length();
            if (size > 0 && size == pendingSize) {
                File shot = pending;
                pending = null;
                lastHandledMtime = Math.max(lastHandledMtime, shot.lastModified());
                copyAsync(shot);
            } else {
                pendingSize = size;
            }
            return;
        }

        File dir = new File(Minecraft.getInstance().gameDirectory, "screenshots");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".png"));
        if (files == null) return;

        File newest = null;
        for (File f : files) {
            long m = f.lastModified();
            if (m > lastHandledMtime && (newest == null || m > newest.lastModified())) newest = f;
        }
        if (newest != null) {
            pending = newest;
            pendingSize = newest.length();
        }
    }

    private void copyAsync(File shot) {
        Thread t = new Thread(() -> {
            try {
                BufferedImage img = javax.imageio.ImageIO.read(shot);
                if (img == null) throw new IllegalStateException("unreadable PNG");
                // Windows clipboard DIB conversion mangles alpha — flatten to RGB.
                BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                var g = rgb.createGraphics();
                g.drawImage(img, 0, 0, null);
                g.dispose();
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new ImageTransferable(rgb), null);
                if (toast.get()) {
                    NotificationManager.show("Screenshot copied to clipboard",
                            NotificationManager.Type.SUCCESS);
                }
            } catch (Throwable e) {
                KitsuneClient.LOGGER.warn("[ScreenshotClipboard] copy failed: {}", e.toString());
                if (toast.get()) {
                    NotificationManager.show("Couldn't copy screenshot: " + e.getMessage(),
                            NotificationManager.Type.WARNING);
                }
            }
        }, "kitsune-screenshot-clipboard");
        t.setDaemon(true);
        t.start();
    }

    private record ImageTransferable(BufferedImage image) implements Transferable {
        @Override public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{ DataFlavor.imageFlavor };
        }
        @Override public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }
        @Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
            return image;
        }
    }
}
