package com.tongji.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.Suggestion;

import co.elastic.clients.elasticsearch.core.search.Hit;
import com.tongji.knowpost.api.dto.FeedItemResponse;
import com.tongji.counter.service.CounterService;
import com.tongji.search.api.dto.SearchResponse;
import com.tongji.search.api.dto.SuggestResponse;
import com.tongji.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 搜索服务实现：
 * - 宽召回（multi_match 命中 title^3 与 body）+ 业务加权（function_score）
 * - 过滤（status=published、可选 tags）+ 排序（score/publish_time/like/view/content_id）
 * - 高亮片段合并为 snippet；游标分页使用 search_after
 */
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    private final ElasticsearchClient es;
    private final CounterService counterService;
    /**
     * ES 索引名：zhiguang 内容统一索引。
     */
    private static final String INDEX = "zhiguang_content_index";

    /**
     * 关键词检索：相关性 + 互动数据加权，支持游标分页与高亮。
     */
    @SuppressWarnings("unchecked")
    public SearchResponse search(String q, int size, String tagsCsv, String after, Long currentUserIdNullable) {
        List<String> tags = parseCsv(tagsCsv);
        List<FieldValue> afterValues = parseAfter(after);

        // 复合排序：优先相关性，其次发布时间与互动数据，最后按 content_id 稳定排序
        List<SortOptions> sorts = new ArrayList<>();
        sorts.add(SortOptions.of(s -> s.score(o -> o.order(SortOrder.Desc))));
        sorts.add(SortOptions.of(s -> s.field(f -> f.field("publish_time").order(SortOrder.Desc))));
        sorts.add(SortOptions.of(s -> s.field(f -> f.field("like_count").order(SortOrder.Desc))));
        sorts.add(SortOptions.of(s -> s.field(f -> f.field("view_count").order(SortOrder.Desc))));
        sorts.add(SortOptions.of(s -> s.field(f -> f.field("content_id").order(SortOrder.Desc))));

        // 完整包名，不然和自定义的 SearchResponse 冲突
        co.elastic.clients.elasticsearch.core.SearchResponse<Map<String, Object>> resp;
        try {
            resp = es.search(s -> {
                var b = s.index(INDEX)
                        .size(size)
                        // 召回与加权：先构造 bool 查询，再用 function_score 做互动数据加权
                        .query(qb -> qb.functionScore(fs -> fs
                                .query(qb2 -> qb2.bool(bq -> {
                                    bq.must(m -> m.multiMatch(mm -> mm.query(q)
                                            .fields("title^3", "body")));
                                    bq.filter(f -> f.term(t -> t.field("status")
                                            .value(v -> v.stringValue("published"))));

                                    if (tags != null && !tags.isEmpty()) {
                                        bq.filter(f -> f.terms(t -> t.field("tags")
                                                .terms(tv -> tv.value(tags.stream().map(FieldValue::of).toList()))));
                                    }
                                    return bq;
                                }))
                                // 对点赞数收藏数设置权重
                                .functions(fn -> fn.fieldValueFactor(fvf -> fvf.field("like_count")
                                        .modifier(FieldValueFactorModifier.Log1p))
                                        .weight(2.0))
                                .functions(fn -> fn.fieldValueFactor(fvf -> fvf.field("view_count")
                                        .modifier(FieldValueFactorModifier.Log1p))
                                        .weight(1.0))
                                .boostMode(FunctionBoostMode.Sum)
                        ))
                        // 返回 title/body 高亮片段，后续合并为 snippet
                        .highlight(h -> h
                                .fields("title", new HighlightField.Builder().build())
                                .fields("body", new HighlightField.Builder().build())
                        )
                        .sort(sorts);
                // 游标分页：携带上一次最后命中的 sort 值
                if (afterValues != null && !afterValues.isEmpty()) {
                    b = b.searchAfter(afterValues);
                }

                return b;
            }, (Class<Map<String, Object>>)(Class<?>) Map.class);
        } catch (Exception e) {
            return new SearchResponse(Collections.emptyList(), null, false);
        }

        List<FeedItemResponse> items = new ArrayList<>();
        List<Hit<Map<String, Object>>> hits = resp.hits() == null ? Collections.emptyList() : resp.hits().hits();

        for (Hit<Map<String, Object>> hit : hits) {
            Map<String, Object> source = hit.source();
            if (source == null) {
                continue;
            }
            String id = asString(source.get("content_id"));
            String title = asString(source.get("title"));
            String descriptionFromDoc = asString(source.get("description"));
            String snippet = buildSnippet(hit);
            String description = (snippet != null && !snippet.isBlank()) ? snippet : descriptionFromDoc;
            List<String> tagList = asStringList(source.get("tags"));
            List<String> imgs = asStringList(source.get("img_urls"));
            String cover = imgs.isEmpty() ? null : imgs.getFirst();
            String authorAvatar = asString(source.get("author_avatar"));
            String authorNickname = asString(source.get("author_nickname"));
            String tagJson = asString(source.get("author_tag_json"));
            Long likeCount = asLong(source.get("like_count"));
            Long favoriteCount = asLong(source.get("favorite_count"));
            Boolean liked = currentUserIdNullable != null && counterService.isLiked("knowpost", id, currentUserIdNullable);
            Boolean faved = currentUserIdNullable != null && counterService.isFaved("knowpost", id, currentUserIdNullable);
            items.add(new FeedItemResponse(
                    id,
                    title,
                    description,
                    cover,
                    tagList,
                    authorAvatar,
                    authorNickname,
                    tagJson,
                    likeCount,
                    favoriteCount,
                    liked,
                    faved,
                    null
            ));
        }

        String nextAfter = null;
        boolean hasMore = items.size() >= size;

        if (!hits.isEmpty()) {
            List<FieldValue> sv = hits.getLast().sort();
            if (sv != null && !sv.isEmpty()) {
                List<String> parts = sv.stream().map(this::fieldValueToString).collect(Collectors.toList());
                nextAfter = Base64.getUrlEncoder().withoutPadding().encodeToString(String.join(",", parts).getBytes());
            }
        }

        return new SearchResponse(items, nextAfter, hasMore);
    }

    /**
     * 联想建议：Completion Suggester，取 title_suggest 的候选文本。
     */
    @SuppressWarnings("unchecked")
    public SuggestResponse suggest(String prefix, int size) {
        co.elastic.clients.elasticsearch.core.SearchResponse<Map<String, Object>> resp;
        try {
            resp = es.search(s -> s.index(INDEX)
                    .suggest(sug -> sug.suggesters("title_suggest",
                            sc -> sc.prefix(prefix).completion(c -> c.field("title_suggest").size(size))))
                    , (Class<Map<String, Object>>)(Class<?>) Map.class);
        } catch (Exception e) {
            return new SuggestResponse(Collections.emptyList());
        }
        List<String> items = new ArrayList<>();
        try {
            var sugg = resp.suggest();
            List<Suggestion<Map<String, Object>>> entry = sugg == null ? null : sugg.get("title_suggest");
            if (entry != null) {
                for (var s : entry) {
                    var comp = s.completion();
                    if (comp != null && comp.options() != null) {
                        for (var opt : comp.options()) {
                            String text = opt.text();
                            if (text != null && !text.isBlank()) {
                                items.add(text);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return new SuggestResponse(items);
    }

    /**
     * 逗号分隔字符串解析为列表；空字符串返回 null。
     */
    private List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }

        String[] parts = csv.split(",");
        List<String> out = new ArrayList<>();

        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }

        }
        return out;
    }

    /**
     * 解析 Base64URL 游标为 sort 值数组，按顺序还原各 FieldValue。
     */
    private List<FieldValue> parseAfter(String after) {
        if (after == null || after.isBlank()) {
            return null;
        }

        try {
            String decoded = new String(Base64.getUrlDecoder().decode(after));
            String[] parts = decoded.split(",");
            List<FieldValue> out = new ArrayList<>(parts.length);

            for (int i = 0; i < parts.length; i++) {
                String p = parts[i];
                if (i == 0) {
                    out.add(FieldValue.of(Double.parseDouble(p)));
                } else if (i == 1) {
                    out.add(FieldValue.of(Long.parseLong(p)));
                } else {
                    out.add(FieldValue.of(Long.parseLong(p)));
                }
            }

            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 合并高亮片段为 snippet（标题片段在前，正文片段在后）。
     */
    private String buildSnippet(Hit<Map<String, Object>> hit) {
        StringBuilder sb = new StringBuilder();

        if (hit.highlight() != null) {
            List<String> ht = hit.highlight().get("title");
            if (ht != null && !ht.isEmpty()) {
                sb.append(String.join(" ", ht));
            }

            List<String> hb = hit.highlight().get("body");
            if (hb != null && !hb.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append(" ");
                }
                sb.append(String.join(" ", hb));
            }
        }

        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * 将 sort 的 FieldValue 安全转换为字符串，便于编码游标。
     */
    private String fieldValueToString(FieldValue fv) {
        if (fv.isDouble()) {
            return String.valueOf(fv.doubleValue());
        }
        if (fv.isLong()) {
            return String.valueOf(fv.longValue());
        }
        if (fv.isString()) {
            return fv.stringValue();
        }
        if (fv.isBoolean()) {
            return String.valueOf(fv.booleanValue());
        }

        return String.valueOf(fv._get());
    }

    /**
     * 任意对象转字符串，null 保护。
     */
    private String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * 任意对象转 Long（Number 直接转换，字符串容错解析）。
     */
    private Long asLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }

        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }

    private Boolean asBoolean(Object o) {
        switch (o) {
            case null -> {
                return null;
            }
            case Boolean b -> {
                return b;
            }
            case Number n -> {
                return n.intValue() != 0;
            }
            default -> {
            }
        }

        String s = String.valueOf(o).toLowerCase();
        if ("true".equals(s)) {
            return Boolean.TRUE;
        }
        if ("false".equals(s)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * 任意对象转 List<String>（支持原生 List 与简单 JSON 数组字符串）。
     */
    private List<String> asStringList(Object o) {
        if (o == null) {
            return Collections.emptyList();
        }

        if (o instanceof List<?> l) {
            List<String> out = new ArrayList<>(l.size());
            for (Object e : l) {
                if (e != null) {
                    out.add(String.valueOf(e));
                }
            }
            return out;
        }

        String s = String.valueOf(o);
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
            if (s.isBlank()) {
                return Collections.emptyList();
            }

            String[] parts = s.split(",");
            List<String> out = new ArrayList<>();
            for (String p : parts) {
                String t = p.trim();
                if (t.startsWith("\"") && t.endsWith("\"")) {
                    t = t.substring(1, t.length() - 1);
                }

                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
            return out;
        }
        return Collections.emptyList();
    }
}
