package com.oskargerliczkowalczuk.chatai;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.*;

import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class ChatAIPlugin extends JavaPlugin implements Listener {

    private String openAiKey;
    private int maxMessagesPerDay;
    private int maxMessageLength;
    private boolean deleteBadMessages;
    private int scanEveryXMessages;

    private int globalMessageCount = 0;
    private int queuedMessageCount = 0;
    private long lastGlobalCountReset = System.currentTimeMillis();

    private final List<String> messageQueue = new ArrayList<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private OkHttpClient httpClient;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();

        openAiKey = config.getString("openai_api_key");
        if (openAiKey == null || openAiKey.isEmpty()) {
            getLogger().severe("OpenAI API key is missing in config.yml!");
        }

        maxMessagesPerDay = config.getInt("max_messages_per_day", 1000);
        maxMessageLength = config.getInt("max_message_length", 200);
        deleteBadMessages = config.getBoolean("delete_bad_messages", true);
        scanEveryXMessages = config.getInt("scan_every_x_messages", 5);

        httpClient = new OkHttpClient();

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("ChatAI Plugin Enabled. Will scan every " + scanEveryXMessages + " messages.");
    }

    @Override
    public void onDisable() {
        executor.shutdown();
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        // Reset on 24h
        if (System.currentTimeMillis() - lastGlobalCountReset >= 24L * 60L * 60L * 1000L) {
            globalMessageCount = 0;
            lastGlobalCountReset = System.currentTimeMillis();
        }

        // Block messages bigger than 200
        if (message.length() > maxMessageLength) {
            event.setCancelled(true);
            player.sendMessage("§cYour message is too long and was blocked.");
            return;
        }

        // Block message with /prompt
        if (message.toLowerCase().startsWith("/prompt")) {
            event.setCancelled(true);
            player.sendMessage("§cUsing prompts in chat is not allowed.");
            return;
        }

        // Add to que que
        synchronized (messageQueue) {
            messageQueue.add(player.getUniqueId() + "::" + message);
            queuedMessageCount++;
            globalMessageCount++;
        }

        // Check for limit
        if (queuedMessageCount >= scanEveryXMessages) {
            processQueueAsync();
        }
    }

    private void processQueueAsync() {
        List<String> toProcess;
        synchronized (messageQueue) {
            if (messageQueue.isEmpty()) return;
            toProcess = new ArrayList<>(messageQueue);
            messageQueue.clear();
            queuedMessageCount = 0;
        }

        executor.submit(() -> {
            for (String entry : toProcess) {
                String[] parts = entry.split("::", 2);
                UUID playerId = UUID.fromString(parts[0]);
                String message = parts[1];

                try {
                    boolean isBad = isMessageBad(message);
                    Player player = Bukkit.getPlayer(playerId);
                    if (isBad && player != null && deleteBadMessages) {
                        Bukkit.getScheduler().runTask(this, () -> {
                            // 1.8.8 Dont allow to unsend the message
                            player.sendMessage("§cYour message was removed due to inappropriate content.");
                        });
                        getLogger().info("Blocked message from " + (player != null ? player.getName() : playerId) + ": " + message);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    getLogger().warning("Error processing message: " + e.getMessage());
                }
            }
        });
    }

    private boolean isMessageBad(String message) throws Exception {
        // Prompt, etc
        JSONArray messages = new JSONArray();

        JSONObject systemMsg = new JSONObject();
        systemMsg.put("role", "system");
        systemMsg.put("content",
            "You are a content moderator. Messages are mostly in Polish. " +
            "Only reply 'bad' if the message is clearly toxic, abusive, or inappropriate. " +
            "Otherwise reply 'ok'. Do not block harmless messages."
        );

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", message);

        messages.put(systemMsg);
        messages.put(userMsg);

        JSONObject json = new JSONObject();
        json.put("model", "gpt-3.5-turbo"); // Model to chose
        json.put("messages", messages);
        json.put("max_tokens", 10);

        MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(JSON_TYPE, json.toString());

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer " + openAiKey)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                getLogger().warning("OpenAI API call failed with code: " + response.code());
                return false;
            }

            String respText = response.body().string();
            JSONObject respJson = new JSONObject(respText);
            JSONArray choices = respJson.getJSONArray("choices");
            if (choices.length() == 0) return false;

            String content = choices.getJSONObject(0).getJSONObject("message").getString("content").toLowerCase();
            return content.contains("bad");
        }
    }
}
