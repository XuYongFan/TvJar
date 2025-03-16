package com.github.catvod.spider;

import android.os.Build;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Misc;
import com.github.catvod.utils.okhttp.OkHttpUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;


/**
 * Author: @SDL
 */
     
    public class KuyunApi {

    private final OkHttpClient client;
    private final Gson gson;
    private final JsonParser jsonParser;

    // 分类映射
    private static final Map<Integer, String> CATEGORY_MAP = new HashMap<Integer, String>() {{
        put(1, "영화");
        put(2, "드라마");
        put(3, "연예오락");
        put(4, "시사");
        put(5, "애니");
        put(6, "OTT예능");
        put(9, "지난드라마");
    }};

    public KuyunApi() {
        this.client = new OkHttpClient.Builder()
                .addInterceptor(new RetryInterceptor(3))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .build();

        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();
        
        this.jsonParser = new JsonParser();
    }

    /**
     * 分类数据入口
     */
    public String category(int type, int page) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("page", page);
            result.put("pagecount", 999);
            result.put("list", getCategoryData(type, page));
            return gson.toJson(result);
        } catch (Exception e) {
            return errorJson(e.getMessage());
        }
    }

    /**
     * 详情页入口
     */
    public String detail(String id) {
        try {
            return gson.toJson(getDetailData(id));
        } catch (Exception e) {
            return errorJson(e.getMessage());
        }
    }

    /**
     * 搜索入口
     */
    public String search(String keyword, int page) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("list", getSearchData(keyword, page));
            return gson.toJson(result);
        } catch (Exception e) {
            return errorJson(e.getMessage());
        }
    }

    /**
     * 播放解析
     */
    public String play(String url) {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .head()
                    .build();

            Response response = client.newCall(request).execute();
            String realUrl = response.request().url().toString();
            
            Map<String, Object> result = new HashMap<>();
            result.put("parse", 0);
            result.put("playUrl", "");
            result.put("url", realUrl);
            
            return gson.toJson(result);
        } catch (Exception e) {
            return errorJson(e.getMessage());
        }
    }

    /******************** 核心方法 ********************/
    
    private List<Map<String, Object>> getCategoryData(int type, int page) throws Exception {
        HttpUrl url = new HttpUrl.Builder()
                .scheme("https")
                .host("api.theboda1.com")
                .addPathSegments("user/disp")
                .addQueryParameter("type", "type_disp")
                .addQueryParameter("type0", String.valueOf(type))
                .addQueryParameter("page", String.valueOf(page))
                .build();

        String json = fetch(url.toString());
        return parseVideoList(json);
    }

    private Map<String, Object> getDetailData(String id) throws Exception {
        HttpUrl url = new HttpUrl.Builder()
                .scheme("https")
                .host("api.theboda1.com")
                .addPathSegments("user/disp")
                .addQueryParameter("type", "video_detail")
                .addQueryParameter("ids", id)
                .build();

        String json = fetch(url.toString());
        return parseDetail(json);
    }

    private List<Map<String, Object>> getSearchData(String keyword, int page) throws Exception {
        HttpUrl url = new HttpUrl.Builder()
                .scheme("https")
                .host("api.theboda1.com")
                .addPathSegments("user/disp")
                .addQueryParameter("type", "search")
                .addQueryParameter("keys", keyword)
                .addQueryParameter("page", String.valueOf(page))
                .build();

        String json = fetch(url.toString());
        return parseVideoList(json);
    }

    /******************** 解析方法 ********************/
    
    private List<Map<String, Object>> parseVideoList(String json) {
        JsonObject data = jsonParser.parse(json).getAsJsonObject();
        JsonArray items = data.getAsJsonArray("data");
        
        List<Map<String, Object>> list = new ArrayList<>();
        for (JsonElement item : items) {
            JsonObject obj = item.getAsJsonObject();
            
            // 广告过滤
            if (isAdvertisement(obj)) continue;
            
            Map<String, Object> video = new LinkedHashMap<>();
            video.put("vod_id", obj.get("ids").getAsString());
            video.put("vod_name", obj.get("title").getAsString());
            video.put("vod_pic", fixUrl(obj.get("img").getAsString()));
            video.put("vod_remarks", formatEpisodes(obj.get("ending").getAsString()));
            list.add(video);
        }
        return list;
    }

    private Map<String, Object> parseDetail(String json) {
        JsonObject data = jsonParser.parse(json).getAsJsonObject();
        JsonObject main = data.getAsJsonArray("data").get(0).getAsJsonObject();
        JsonArray playList = data.getAsJsonArray("playlist");
        
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("vod_id", main.get("ids").getAsString());
        detail.put("vod_name", main.get("title").getAsString());
        detail.put("vod_pic", fixUrl(main.get("img").getAsString()));
        detail.put("vod_year", parseYear(main.get("ending").getAsString()));
        detail.put("vod_content", main.has("intro") ? main.get("intro").getAsString() : "");
        
        // 播放源处理
        List<String> playFrom = new ArrayList<>();
        List<String> playUrl = new ArrayList<>();
        for (JsonElement item : playList) {
            JsonObject play = item.getAsJsonObject();
            playFrom.add(play.get("from").getAsString());
            playUrl.add(play.get("playurl").getAsString());
        }
        detail.put("vod_play_from", String.join("$$$", playFrom));
        detail.put("vod_play_url", String.join("$$$", playUrl));
        
        return Collections.singletonMap("list", Collections.singletonList(detail));
    }

    /******************** 工具方法 ********************/
    
    private String fetch(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Referer", "https://www.theboda1.com/")
                .addHeader("User-Agent", randomUA())
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code: " + response);
            }
            ResponseBody body = response.body();
            return body != null ? body.string() : "";
        }
    }

    private String fixUrl(String url) {
        return url.replace("\\/", "/");
    }

    private boolean isAdvertisement(JsonObject item) {
        // 广告过滤规则
        return item.has("today") && item.get("today").getAsInt() == 99;
    }

    private String randomUA() {
        String[] uas = {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 16_5 like Mac OS X) AppleWebKit/605.1.15",
                "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36"
        };
        return uas[new Random().nextInt(uas.length)];
    }

    private String errorJson(String msg) {
        return "{\"code\":500,\"msg\":\"" + msg + "\"}";
    }

    /******************** 拦截器 ********************/
    
    static class RetryInterceptor implements Interceptor {
        private final int maxRetries;

        public RetryInterceptor(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            Response response = null;
            IOException exception = null;

            for (int i = 0; i <= maxRetries; i++) {
                try {
                    response = chain.proceed(request);
                    if (response.isSuccessful()) {
                        return response;
                    }
                } catch (IOException e) {
                    exception = e;
                }
            }

            if (exception != null) throw exception;
            if (response != null) return response;
            throw new IOException("Unknown error after " + maxRetries + " retries");
        }
    }

    /******************** 辅助方法 ********************/
    
    private String formatEpisodes(String ending) {
        try {
            long timestamp = Long.parseLong(ending);
            return new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(timestamp * 1000));
        } catch (NumberFormatException e) {
            return ending;
        }
    }

    private String parseYear(String ending) {
        try {
            long timestamp = Long.parseLong(ending);
            return new java.text.SimpleDateFormat("yyyy").format(new java.util.Date(timestamp * 1000));
        } catch (NumberFormatException e) {
            return "2023";
        }
    }

    /******************** 测试入口 ********************/
    
    public static void main(String[] args) {
        KuyunApi api = new KuyunApi();
        
        // 测试分类
        System.out.println("分类测试:");
        System.out.println(api.category(1, 1));
        
        // 测试搜索
        System.out.println("\n搜索测试:");
        System.out.println(api.search("트롯", 1));
        
        // 测试详情（需要真实ID）
        // System.out.println("\n详情测试:");
        // System.out.println(api.detail("c640cab3d82411efbcc7d24c1d00000f"));
    }
}
