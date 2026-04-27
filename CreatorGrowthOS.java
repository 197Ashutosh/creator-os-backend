import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CreatorGrowthOS {

    private static final String GROQ_API_KEY = System.getenv("GROQ_API_KEY");
    private static final String YOUTUBE_API_KEY = System.getenv("YOUTUBE_API_KEY");
    private static final String DB_URL = System.getenv("DB_URL"); 
    private static final String DB_USER = System.getenv("DB_USER");
    private static final String DB_PASS = System.getenv("DB_PASS"); 

    public static void main(String[] args) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        String portStr = System.getenv("PORT");
        int port = (portStr != null) ? Integer.parseInt(portStr) : 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/youtube", new YouTubeHandler());
        server.createContext("/api/recommendation", new RecommendationHandler());
        server.setExecutor(null); 
        server.start();
        System.out.println("Master, Creator Growth OS live on port: " + port);
    }

    static class YouTubeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(204, -1); return; }
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    InputStream is = exchange.getRequestBody();
                    String requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    String channelId = extractJsonValue(requestBody, "channelId");
                    
                    String ytData = fetchYouTubeStats(channelId);
                    registerChannelInDB(channelId);
                    
                    byte[] responseBytes = ytData.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                    exchange.sendResponseHeaders(200, responseBytes.length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(responseBytes);
                    os.close();
                } catch (Exception e) { sendError(exchange, "YT Handler Error: " + e.getMessage()); }
            }
        }
    }

    static class RecommendationHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(204, -1); return; }
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    InputStream is = exchange.getRequestBody();
                    String requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    
                    String channelId = extractJsonValue(requestBody, "channelId");
                    String subs = extractJsonValue(requestBody, "subs"); 
                    String category = extractJsonValue(requestBody, "category");
                    String viewsRaw = extractJsonValue(requestBody, "views");
                    String engagementRaw = extractJsonValue(requestBody, "engagement");
                    
                    // SAFETY 1: Clean numbers to prevent NumberFormatException crashes
                    int views = 0;
                    double engagement = 0.0;
                    try { views = Integer.parseInt(viewsRaw.replaceAll("[^0-9]", "")); } catch (Exception ignore) {}
                    try { engagement = Double.parseDouble(engagementRaw.replaceAll("[^0-9.]", "")); } catch (Exception ignore) {}
                    
                    saveMetricsAndIncrementStreak(channelId, category, views, engagement);
                    
                    String aiRecommendation = getAdviceFromGroq(subs, category, String.valueOf(views), String.valueOf(engagement));
                    
                    String jsonResponse = "{\"recommendation\": \"" + aiRecommendation + "\"}";
                    byte[] responseBytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
                    
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                    exchange.sendResponseHeaders(200, responseBytes.length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(responseBytes);
                    os.close();
                } catch (Exception e) { sendError(exchange, "Recommendation Logic Error: " + e.getMessage()); }
            }
        }
    }

    private static void setCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS, GET");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    private static void sendError(HttpExchange exchange, String message) throws IOException {
        // SAFETY 2: Ensure error messages don't break JSON format
        String safeMsg = message == null ? "Unknown Error" : message.replace("\"", "'").replaceAll("[\\x00-\\x1F]", " ");
        String jsonResponse = "{\"error\": \"" + safeMsg + "\"}";
        byte[] responseBytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(500, responseBytes.length);
        exchange.getResponseBody().write(responseBytes);
        exchange.getResponseBody().close();
    }

    private static String fetchYouTubeStats(String channelId) {
        try {
            String url = "https://www.googleapis.com/youtube/v3/channels?part=snippet,statistics&id=" + channelId + "&key=" + YOUTUBE_API_KEY;
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            
            String title = extractJsonValue(body, "title");
            String thumb = extractJsonValue(body, "url").replace("\\/", "/"); 
            String subs = extractJsonValue(body, "subscriberCount");
            String views = extractJsonValue(body, "viewCount");
            String vids = extractJsonValue(body, "videoCount");
            
            return String.format("{\"title\":\"%s\", \"thumb\":\"%s\", \"subs\":\"%s\", \"views\":\"%s\", \"videos\":\"%s\"}", title, thumb, subs, views, vids);
        } catch (Exception e) { return "{\"error\": \"YT API Error\"}"; }
    }

    private static void registerChannelInDB(String channelId) {
        String query = "INSERT IGNORE INTO Creators (channel_id, current_streak) VALUES (?, 0)";
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, channelId);
            pstmt.executeUpdate();
        } catch (SQLException e) { } // Intentionally ignored so DB failure doesn't crash the AI response
    }

    private static void saveMetricsAndIncrementStreak(String channelId, String category, int views, double engagement) {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
            PreparedStatement p1 = conn.prepareStatement("INSERT INTO ContentMetrics (channel_id, category, views, engagement_rate) VALUES (?,?,?,?)");
            p1.setString(1, channelId); p1.setString(2, category); p1.setInt(3, views); p1.setDouble(4, engagement);
            p1.executeUpdate();
            PreparedStatement p2 = conn.prepareStatement("UPDATE Creators SET current_streak = current_streak + 1 WHERE channel_id = ?");
            p2.setString(1, channelId); p2.executeUpdate();
        } catch (SQLException e) { }
    }

    private static String getAdviceFromGroq(String subs, String category, String views, String engagement) {
        try {
            String prompt = "Act as an expert YouTube strategist. My channel has " + subs + " subscribers. I recently uploaded a " + category + " video that received " + views + " views and an engagement rate of " + engagement + "%. Based on this performance, predict a short roadmap for my channel and suggest 2 specific video topics I should post next to maximize my growth.";
            
            String cleanPrompt = prompt.replace("\"", "'").replaceAll("[\\n\\r]", " ");
            String payload = "{\"model\": \"llama-3.1-8b-instant\",\"messages\": [{\"role\": \"user\", \"content\": \"" + cleanPrompt + "\"}],\"temperature\": 0.7}";
            
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                    .header("Authorization", "Bearer " + GROQ_API_KEY).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
            
            return extractGroqContent(client.send(request, HttpResponse.BodyHandlers.ofString()).body());
        } catch (Exception e) { return "Stay consistent and monitor analytics. (Error generating AI roadmap)"; }
    }

    private static String extractJsonValue(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"?([^\"},]+)\"?").matcher(json);
        return m.find() ? m.group(1).trim() : "0";
    }

    // SAFETY 3: Bulletproof JSON Extractor for complex AI text streams
    private static String extractGroqContent(String json) {
        try {
            if (json == null || json.contains("\"error\"")) return "Groq API error. Check Render Logs.";
            
            String marker = "\"content\":";
            int idx = json.indexOf(marker);
            if (idx == -1) return "Could not parse AI response structure.";
            
            idx += marker.length();
            while (idx < json.length() && Character.isWhitespace(json.charAt(idx))) idx++;
            
            if (idx < json.length() && json.charAt(idx) == '"') {
                idx++; 
                StringBuilder sb = new StringBuilder();
                while (idx < json.length()) {
                    char c = json.charAt(idx);
                    if (c == '\\') {
                        idx++;
                        if (idx < json.length()) {
                            char next = json.charAt(idx);
                            if (next == 'n') sb.append("<br><br>");
                            else if (next == '"') sb.append("'");
                            else if (next == 't') sb.append(" ");
                            else sb.append(next);
                        }
                    } else if (c == '"') {
                        break; 
                    } else {
                        sb.append(c);
                    }
                    idx++;
                }
                String res = sb.toString();
                res = res.replace("\"", "'"); 
                res = res.replaceAll("[\\x00-\\x1F]", ""); // Absolute scrub of rogue control characters
                return res;
            }
            return "Unexpected AI output format.";
        } catch (Exception e) { return "Extraction engine error."; }
    }
}
