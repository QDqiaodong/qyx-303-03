package com.example.tuanjian.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对比/落地入口引用的约束模板。
 * 条件值一律以数据库中该模板的当前记录为准，客户端不能用旧页面里的条件覆盖。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateReferenceRequest {

    @NotNull(message = "约束模板编号不能为空")
    @JsonAlias("id")
    private Long templateId;

}
