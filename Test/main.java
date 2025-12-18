package com.oskargerliczkowalczuk.chatai;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import okhttp3.*;
import org.json.*;

import java.util.*;
import java.util.concurrent.*;

public class ChatAIPlugin extends JavaPlugin implements Listener {

    /* ===================== CONFIG ===================== */

    private String openAiKey;
    private String aiModel; // gpt-5-mini or gpt-5-nano
    private int batchSize;  // how many messages per OpenAI request
    private int maxMessageLength;
    private long muteDurationMillis;

    /* ===================== STATE ===================== */

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final OkHttpClient httpClient = new OkHttpClient();

    // Batch queue: messages waiting for AI moderation
    private final List<ChatEntry> batchQueue = new ArrayList<>();

    // Active mutes: UUID -> mute end timestamp
    private final Map<UUID, Long> mutedPlayers = new ConcurrentHashMap<>();

    /* ===================== LIFECYCLE ===================== */

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration c = getConfig();

        openAiKey = c.getString("openai_api_key");
        aiModel = c.getString("ai_model", "gpt-5-mini");
        batchSize = c.getInt("batch_size", 5);
        maxMessageLength = c.getInt("max_message_length", 200);
        muteDurationMillis = c.getLong("mute_minutes", 15) * 60_000L;

        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("ChatAI enabled | model=" + aiModel + " | batchSize=" + batchSize);
    }

    @Override
    public void onDisable() {
        executor.shutdownNow();
    }

    /* ===================== CHAT HANDLER ===================== */

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String message = event.getMessage();

        // Mute check
        if (isMuted(uuid)) {
            event.setCancelled(true);
            player.sendMessage("§cYou are muted for another " + getMuteLeft(uuid) + " minutes.");
            return;
        }

        // Length check
        if (message.length() > maxMessageLength) {
            event.setCancelled(true);
            player.sendMessage("§cYour message is too long.");
            return;
        }

        // Add message to batch queue (non-blocking chat)
        synchronized (batchQueue) {
            batchQueue.add(new ChatEntry(uuid, player.getName(), message));
            if (batchQueue.size() >= batchSize) {
                processBatch();
            }
        }
    }

    /* ===================== BATCH PROCESSING ===================== */

    private void processBatch() {
        List<ChatEntry> batch;

        synchronized (batchQueue) {
            batch = new ArrayList<>(batchQueue);
            batchQueue.clear();
        }

        executor.submit(() -> {
            try {
                Map<Integer, Boolean> results = moderateBatch(batch);

                Bukkit.getScheduler().runTask(this, () -> {
                    for (int i = 0; i < batch.size(); i++) {
                        if (results.getOrDefault(i, false)) {
                            UUID uuid = batch.get(i).uuid;
                            Player p = Bukkit.getPlayer(uuid);
                            if (p != null) {
                                mute(uuid);
                                p.sendMessage("§cInappropriate language detected. You have been muted.");
                            }
                        }
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /* ===================== AI MODERATION ===================== */

    private Map<Integer, Boolean> moderateBatch(List<ChatEntry> batch) throws Exception {
        JSONArray messages = new JSONArray();

        messages.put(new JSONObject()
                .put("role", "system")
                .put("content",
                        "You are a chat moderator. You will receive multiple chat messages. " +
                        "For EACH message reply with 'bad' or 'ok' in the SAME ORDER, separated by commas. " +
                        "Language: Polish."));

        StringBuilder userContent = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            userContent.append(i + 1).append(". ").append(batch.get(i).message).append("\n");
        }

        messages.put(new JSONObject()
                .put("role", "user")
                .put("content", userContent.toString()));

        JSONObject json = new JSONObject()
                .put("model", aiModel)
                .put("messages", messages)
                .put("max_tokens", batch.size() * 2);

        RequestBody body = RequestBody.create(
                MediaType.parse("application/json"), json.toString());

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer " + openAiKey)
                .post(body)
                .build();

        Map<Integer, Boolean> resultMap = new HashMap<>();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) return resultMap;

            String content = new JSONObject(response.body().string())
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .toLowerCase();

            String[] parts = content.split(",");
            for (int i = 0; i < parts.length && i < batch.size(); i++) {
                resultMap.put(i, parts[i].contains("bad"));
            }
        }

        return resultMap;
    }

    /* ===================== MUTE SYSTEM ===================== */

    private void mute(UUID uuid) {
        mutedPlayers.put(uuid, System.currentTimeMillis() + muteDurationMillis);
    }

    private boolean isMuted(UUID uuid) {
        Long until = mutedPlayers.get(uuid);
        if (until == null) return false;
        if (until < System.currentTimeMillis()) {
            mutedPlayers.remove(uuid);
            return false;
        }
        return true;
    }

    private long getMuteLeft(UUID uuid) {
        return Math.max(0, (mutedPlayers.get(uuid) - System.currentTimeMillis()) / 60000);
    }

    /* ===================== DATA ===================== */

    private static class ChatEntry {
        final UUID uuid;
        final String playerName;
        final String message;

        ChatEntry(UUID uuid, String playerName, String message) {
            this.uuid = uuid;
            this.playerName = playerName;
            this.message = message;
        }
    }
}
