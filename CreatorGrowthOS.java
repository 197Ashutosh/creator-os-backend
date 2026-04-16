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

    // =========================================================
    // MASTER, THESE ARE NOW FULLY DYNAMIC FOR RENDER DEPLOYMENT:
    // =========================================================
    private static final String GROQ_API_KEY = System.getenv("GROQ_API_KEY");
    private static final String YOUTUBE_API_KEY = System.getenv("YOUTUBE_API_KEY");
    
    // Database credentials pulled from Render Environment Variables
    private static final String DB_URL = System.getenv("DB_URL"); 
    private static final String DB_USER = System.getenv("DB_USER");
    private static final String DB_PASS = System.getenv("DB_PASS"); 
    // =========================================================

    public static void main(String[] args) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        
        // Render expects the app to listen on a specific port provided in the environment
        String portStr = System.getenv("PORT");
        int port = (portStr != null) ? Integer.parseInt(portStr) : 8080;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        
        server.createContext("/api/youtube", new YouTubeHandler());
        server.createContext("/api/recommendation", new RecommendationHandler());
        
        server.setExecutor(null); 
        server.start();
        System.out.println("Master, the Dynamic Creator Growth OS is live on port: " + port);
    }

    // --- HANDLER 1: FETCH DYNAMIC YOUTUBE CHANNEL ---
    static class YouTubeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) { 
                exchange.sendResponseHeaders(204, -1); 
                return; 
            }

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
                } catch (Exception e) {
                    e.printStackTrace();
                    sendError(exchange, "Java Server Error: " + e.getMessage());
                }
            }
        }
    }

    // --- HANDLER 2: AI STRATEGY & METRICS SAVING ---
    static class RecommendationHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) { 
                exchange.sendResponseHeaders(204, -1); 
                return; 
            }

            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    InputStream is = exchange.getRequestBody();
                    String requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    
                    String channelId = extractJsonValue(requestBody, "channelId");
                    String subs = extractJsonValue(requestBody, "subs"); 
                    String category = extractJsonValue(requestBody, "category");
                    String views = extractJsonValue(requestBody, "views");
                    String engagement = extractJsonValue(requestBody, "engagement");

                    saveMetricsAndIncrementStreak(channelId, category, Integer.parseInt(views), Double.parseDouble(engagement));
                    String aiRecommendation = getAdviceFromGroq(subs, category, views, engagement);

                    String jsonResponse = "{\"recommendation\": \"" + aiRecommendation + "\"}";
                    
                    byte[] responseBytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                    exchange.sendResponseHeaders(200, responseBytes.length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(responseBytes);
                    os.close();
                } catch (Exception e) {
                    e.printStackTrace();
                    sendError(exchange, "Server logic crashed: " + e.getMessage());
                }
            }
        }
    }

    private static void setCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS, GET");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    private static void sendError(HttpExchange exchange, String message) throws IOException {
        String jsonResponse = "{\"error\": \"" + message.replace("\"", "'") + "\"}";
        byte[] responseBytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(500, responseBytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(responseBytes);
        os.close();
    }

    private static String fetchYouTubeStats(String channelId) {
        try {
            String url = "https://www.googleapis.com/youtube/v3/channels?part=statistics&id=" + channelId + "&key=" + YOUTUBE_API_KEY;
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            String body = response.body();
            String subs = extractJsonValue(body, "subscriberCount");
            String totalViews = extractJsonValue(body, "viewCount");
            String videos = extractJsonValue(body, "videoCount");
            
            return "{\"subs\": \"" + subs + "\", \"views\": \"" + totalViews + "\", \"videos\": \"" + videos + "\"}";
        } catch (Exception e) { 
            return "{\"error\": \"Could not fetch YouTube Data\"}"; 
        }
    }

    private static void registerChannelInDB(String channelId) {
        String query = "INSERT IGNORE INTO Creators (channel_id, current_streak) VALUES (?, 0)";
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, channelId);
            pstmt.executeUpdate();
        } catch (SQLException e) { System.out.println("JDBC Error (Register): " + e.getMessage()); }
    }

    private static void saveMetricsAndIncrementStreak(String channelId, String category, int views, double engagement) {
        String insertQuery = "INSERT INTO ContentMetrics (channel_id, category, views, engagement_rate) VALUES (?, ?, ?, ?)";
        String updateStreakQuery = "UPDATE Creators SET current_streak = current_streak + 1 WHERE channel_id = ?";
        
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement pstmtInsert = conn.prepareStatement(insertQuery);
             PreparedStatement pstmtStreak = conn.prepareStatement(updateStreakQuery)) {
            
            pstmtInsert.setString(1, channelId); 
            pstmtInsert.setString(2, category); 
            pstmtInsert.setInt(3, views); 
            pstmtInsert.setDouble(4, engagement);
            pstmtInsert.executeUpdate();
            
            pstmtStreak.setString(1, channelId);
            pstmtStreak.executeUpdate();
        } catch (SQLException e) { System.out.println("JDBC Error (Save/Streak): " + e.getMessage()); }
    }

    private static String getAdviceFromGroq(String subs, String category, String views, String engagement) {
        try {
            String prompt = "A YouTube channel with " + subs + " total subscribers just uploaded a " + category + " video. It got " + views + " views and " + engagement + "% engagement. Give a short, 2-sentence actionable advice on what they should do next to grow.";
            String cleanPrompt = prompt.replace("\"", "'");
            
            String payload = "{\"model\": \"llama-3.1-8b-instant\",\"messages\": [{\"role\": \"user\", \"content\": \"" + cleanPrompt + "\"}],\"temperature\": 0.7}";

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                    .header("Authorization", "Bearer " + GROQ_API_KEY)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();

            return extractGroqContent(client.send(request, HttpResponse.BodyHandlers.ofString()).body());
        } catch (Exception e) { 
            return "Server error while contacting AI."; 
        }
    }

    private static String extractJsonValue(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*\"?([^\"},]+)\"?").matcher(json);
        return matcher.find() ? matcher.group(1).trim() : "0";
    }

    private static String extractGroqContent(String json) {
        try {
            if (json.contains("\"error\"")) {
                System.out.println("\n--- GROQ API ERROR ---\n" + json);
                return "Groq API Error: Check Render logs.";
            }

            String target = "\"content\":\"";
            int start = json.indexOf(target);
            if (start == -1) {
                target = "\"content\": \"";
                start = json.indexOf(target);
            }

            if (start != -1) {
                start += target.length();
                int end = start;
                while (end < json.length()) {
                    if (json.charAt(end) == '"' && json.charAt(end - 1) != '\\') break;
                    end++;
                }
                String cleanAdvice = json.substring(start, end);
                return cleanAdvice.replace("\\n", "<br>").replace("\\\"", "'").replace("\"", "'").replace("\n", "");
            }
            return "Parsing error in AI response.";
        } catch (Exception e) {
            return "Server processing error during AI extraction.";
        }
    }
}
