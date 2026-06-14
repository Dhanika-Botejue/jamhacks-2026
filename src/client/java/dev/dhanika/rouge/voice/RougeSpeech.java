package dev.dhanika.rouge.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Speaks Rouge chat lines via ElevenLabs. Lines are queued and played in order.
 * Each spoken line is read out in full; {@link Tier} only decides whether a line
 * is voiced at all (status lines stay text-only via {@link Tier#OFF}).
 */
public final class RougeSpeech {

    private static final Logger LOGGER = LoggerFactory.getLogger("rouge");

    public enum Tier {
        /** Do not speak (status lines, full text already in chat). */
        OFF,
        /** AI intro / reply — spoken in full. */
        INTRO,
        /** Step instruction — the explanation line, spoken in full. */
        STEP,
        /** Step cleared — the "good job" line, spoken in full. */
        PRAISE,
        /** Error — spoken in full. */
        ERROR
    }

    private static ElevenLabsClient client;
    private static final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private static final AtomicBoolean enabled = new AtomicBoolean(true);
    private static Thread worker;

    private RougeSpeech() {
    }

    public static void init(ElevenLabsConfig config) {
        client = new ElevenLabsClient(config);
        if (!client.hasKey()) {
            LOGGER.info("[Rouge] No {} — chat will be text-only until you set it.",
                    ElevenLabsConfig.TOKEN_ENV_VAR);
            return;
        }
        startWorker();
        LOGGER.info("[Rouge] Voice enabled (ElevenLabs voice {}).", config.voiceId());
    }

    public static boolean isAvailable() {
        return client != null && client.hasKey();
    }

    public static boolean isEnabled() {
        return enabled.get();
    }

    public static void setEnabled(boolean on) {
        enabled.set(on);
        if (!on) {
            stop();
        }
    }

    /** Queue a line for speech with the given brevity tier. */
    public static void speak(String line, Tier tier) {
        if (tier == Tier.OFF || !enabled.get() || client == null || !client.hasKey() || line == null) {
            return;
        }
        String cleaned = forSpeech(line);
        if (cleaned.isBlank()) {
            return;
        }
        queue.offer(cleaned);
    }

    public static void stop() {
        queue.clear();
        PcmAudioPlayer.requestStop();
    }

    public static void shutdown() {
        stop();
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    private static void startWorker() {
        if (worker != null && worker.isAlive()) {
            return;
        }
        worker = new Thread(RougeSpeech::runWorker, "rouge-tts");
        worker.setDaemon(true);
        worker.start();
    }

    private static void runWorker() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String text = queue.take();
                byte[] pcm = client.synthesize(text).join();
                PcmAudioPlayer.play(pcm);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.debug("[Rouge] TTS worker skipped a line: {}", e.getMessage());
            }
        }
    }

    static String forSpeech(String line) {
        if (line == null) return "";
        String t = line.trim();
        if (t.isEmpty()) return "";

        // Strip markdown/decoration that would otherwise be read aloud literally,
        // but keep the full sentence(s) so the voiceover matches the chat text.
        t = t.replaceAll("[`*_#]", "");
        t = t.replace("•", "");
        t = t.replace("✔", "");
        return t.replaceAll("\\s+", " ").trim();
    }
}
