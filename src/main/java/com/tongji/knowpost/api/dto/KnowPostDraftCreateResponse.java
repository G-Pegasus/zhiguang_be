package com.tongji.knowpost.api.dto;

/**
 * 创建草稿响应：返回新建的帖子 ID（字符串避免前端精度丢失）。
 */
public record KnowPostDraftCreateResponse(String id) {

}